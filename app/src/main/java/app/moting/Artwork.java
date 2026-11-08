package app.moting;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

final class Artwork {
    static Bitmap load(String template) throws IOException {
        String url = template.replace("{w}", "240").replace("{h}", "240")
                .replace("{f}", "jpg");
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException e) { throw new IOException("专辑封面地址无效。", e); }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || !(host.endsWith(".mzstatic.com") || host.endsWith(".apple.com"))) {
            throw new IOException("封面地址不是受信任的 Apple HTTPS 图片地址。");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("专辑封面暂时无法下载。");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("封面超过 2 MB，已终止下载。");
                    output.write(buffer, 0, count);
                }
                byte[] bytes = output.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法解析专辑封面。");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 480) {
                    options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
                }
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) throw new IOException("封面解码失败。");
                return bitmap;
            }
        } finally { connection.disconnect(); }
    }
}
