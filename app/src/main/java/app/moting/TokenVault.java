package app.moting;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TokenVault {
    record Tokens(String developer, String user) {
        boolean authorized() { return !developer.isEmpty() && !user.isEmpty(); }
    }

    private final SharedPreferences preferences;
    private final byte[] associatedData;
    private final String keyAlias;
    private final AssetManager assets;
    private String packagedDeveloper;

    TokenVault(Context context) {
        preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE);
        associatedData = context.getPackageName().getBytes(StandardCharsets.UTF_8);
        keyAlias = context.getPackageName() + ".credentials.v1";
        assets = context.getAssets();
    }

    synchronized Tokens read() throws GeneralSecurityException, IOException, JSONException {
        String stored = preferences.getString("encrypted", "");
        if (stored.isEmpty()) return new Tokens(packagedDeveloper(), "");
        JSONObject envelope = new JSONObject(stored);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                decode(envelope.getString("iv"))));
        cipher.updateAAD(associatedData);
        JSONObject value = new JSONObject(new String(cipher.doFinal(
                decode(envelope.getString("ciphertext"))), StandardCharsets.UTF_8));
        return new Tokens(value.getString("developer"), value.getString("user"));
    }

    synchronized void save(Tokens tokens) throws GeneralSecurityException, IOException, JSONException {
        JSONObject value = new JSONObject().put("developer", tokens.developer()).put("user", tokens.user());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(associatedData);
        JSONObject envelope = new JSONObject()
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("ciphertext", Base64.encodeToString(cipher.doFinal(
                        value.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        if (!preferences.edit().putString("encrypted", envelope.toString()).commit()) {
            throw new IOException("无法保存加密凭据，请检查存储空间。");
        }
    }

    synchronized void clear() throws IOException {
        if (!preferences.edit().clear().commit()) throw new IOException("无法清除凭据。");
    }

    private String packagedDeveloper() throws IOException {
        if (packagedDeveloper != null) return packagedDeveloper;
        String name = "developer-token.jwt";
        String[] names = assets.list("");
        if (names == null) throw new IOException("无法读取应用接入配置，请重新安装。");
        if (!java.util.Arrays.asList(names).contains(name)) {
            packagedDeveloper = "";
            return packagedDeveloper;
        }
        try (InputStream input = assets.open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 8192) throw new IOException("应用接入配置过大，请重新安装。");
                output.write(buffer, 0, count);
            }
            String token = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) throw new IOException("应用接入配置为空，请重新安装。");
            packagedDeveloper = token;
            return packagedDeveloper;
        }
    }

    private static byte[] decode(String value) throws IOException {
        try { return Base64.decode(value, Base64.NO_WRAP); }
        catch (IllegalArgumentException exception) {
            throw new IOException("加密凭据格式已损坏，请清除本机凭据后重新连接。", exception);
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(keyAlias)) return (SecretKey) store.getKey(keyAlias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
