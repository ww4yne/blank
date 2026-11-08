package app.moting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

class AppleMusicApi {
    private final TokenVault vault;
    private volatile String storefront = "";

    AppleMusicApi(TokenVault vault) { this.vault = vault; }

    void reset() { storefront = ""; }

    String storefront() throws IOException, JSONException, GeneralSecurityException {
        if (storefront.isEmpty()) {
            JSONArray data = get("/v1/me/storefront").getJSONArray("data");
            if (data.length() == 0) throw new IOException("Apple 未返回账号商店地区，请重新授权。");
            String result = data.getJSONObject(0).getString("id");
            if (!result.matches("[a-z]{2}")) throw new IOException("Apple 返回了无法识别的商店地区。");
            storefront = result;
        }
        return storefront;
    }

    MusicData.Page recommendations() throws IOException, JSONException, GeneralSecurityException {
        return MusicData.recommendations(get("/v1/me/recommendations"));
    }

    MusicData.Page library(String kind) throws IOException, JSONException, GeneralSecurityException {
        if (!kind.equals("playlists") && !kind.equals("albums") && !kind.equals("songs")) {
            throw new IllegalArgumentException("Unsupported library type.");
        }
        return page("/v1/me/library/" + kind + "?limit=25");
    }

    MusicData.Page search(String term, String kind) throws IOException, JSONException, GeneralSecurityException {
        if (!kind.equals("songs") && !kind.equals("albums") && !kind.equals("playlists")) {
            throw new IllegalArgumentException("Unsupported search type.");
        }
        String path = "/v1/catalog/" + storefront() + "/search?types=" + kind + "&limit=25&term="
                + URLEncoder.encode(term, "UTF-8");
        JSONObject results = get(path).getJSONObject("results");
        JSONObject category = results.optJSONObject(kind);
        return category == null ? new MusicData.Page(java.util.List.of(), "") : MusicData.parse(category);
    }

    MusicData.Page tracks(MusicData.Item item) throws IOException, JSONException, GeneralSecurityException {
        String kind = item.playlist() ? "playlists" : "albums";
        String base = item.library() ? "/v1/me/library/" : "/v1/catalog/" + storefront() + "/";
        return trackPage(base + kind + "/" + segment(item.id()) + "/tracks?limit=100");
    }

    MusicData.Page page(String next) throws IOException, JSONException, GeneralSecurityException {
        JSONObject response = get(next);
        JSONArray data = response.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                if (data.getJSONObject(i).optString("type").equals("personal-recommendation")) {
                    return MusicData.recommendations(response);
                }
            }
        }
        return MusicData.parse(response);
    }

    MusicData.Page trackPage(String next) throws IOException, JSONException, GeneralSecurityException {
        return MusicData.parseTracks(get(next));
    }

    String songId(MusicData.Item item) throws IOException, JSONException, GeneralSecurityException {
        if (!item.song()) throw new IOException("此项目不是歌曲。");
        if (!item.catalogId().isEmpty()) return item.catalogId();
        if (!item.library()) {
            throw new IOException("此歌曲在当前账号或地区不可播放，Apple 未返回播放参数。");
        }
        MusicData.Page catalog = page("/v1/me/library/songs/" + segment(item.id()) + "/catalog");
        for (MusicData.Item song : catalog.items()) {
            if (!song.catalogId().isEmpty()) return song.catalogId();
        }
        throw new IOException("“" + item.title() + "”没有可用的 Apple Music 曲库版本；本版不支持个人上传歌曲。");
    }

    void favoriteSong(String id) throws IOException, JSONException, GeneralSecurityException {
        request("/v1/me/favorites?ids%5Bsongs%5D=" + songSegment(id), "POST", null, 202);
    }

    void addSongToLibrary(String id) throws IOException, JSONException, GeneralSecurityException {
        request("/v1/me/library?ids%5Bsongs%5D=" + songSegment(id), "POST", null, 202);
    }

    void suggestLess(String id) throws IOException, JSONException, GeneralSecurityException {
        JSONObject body = new JSONObject().put("type", "ratings")
                .put("attributes", new JSONObject().put("value", -1));
        JSONObject response = request("/v1/me/ratings/songs/" + songSegment(id), "PUT", body, 200);
        JSONArray data = response.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject rating = data.getJSONObject(i);
                JSONObject attributes = rating.optJSONObject("attributes");
                if (id.equals(rating.optString("id")) && "ratings".equals(rating.optString("type"))
                        && attributes != null && attributes.optInt("value") == -1) return;
            }
        }
        throw new IOException("Apple 未确认此歌曲的减少推荐反馈，请稍后重试。");
    }

    private static String songSegment(String id) throws IOException {
        if (id == null || !id.matches("[0-9]+")) throw new IOException("当前歌曲没有可用的曲库 ID，无法执行此操作。");
        return segment(id);
    }

    JSONObject get(String route) throws IOException, JSONException, GeneralSecurityException {
        return request(route, "GET", null, 200);
    }

    JSONObject request(String route, String method, JSONObject body, int expectedStatus)
            throws IOException, JSONException, GeneralSecurityException {
        URI uri = trustedUri(route);
        TokenVault.Tokens tokens = vault.read();
        DeveloperToken.validate(tokens.developer(), System.currentTimeMillis() / 1000);
        if (!tokens.authorized()) throw new IOException("请先在设置中连接 Apple Music。");
        HttpURLConnection connection = openConnection(uri);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + tokens.developer());
            connection.setRequestProperty("Music-User-Token", tokens.user());
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != expectedStatus) {
                String detail = switch (status) {
                    case 401 -> "Developer Token 无效或已过期，请重新配置。";
                    case 403 -> "访问被拒绝，请检查 Apple Music 订阅、授权及所在地区。";
                    case 404 -> "此内容已不可用，或当前地区没有该内容。";
                    case 429 -> "请求过于频繁，请稍后重试。";
                    default -> "Apple Music 请求失败（HTTP " + status + "），请稍后重试。";
                };
                throw new IOException(detail);
            }
            if (expectedStatus == 202 || expectedStatus == 204) return new JSONObject();
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8 * 1024 * 1024) throw new IOException("API 响应超过 8 MB，已终止读取。");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    HttpURLConnection openConnection(URI uri) throws IOException {
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    static URI trustedUri(String route) throws IOException {
        try {
            URI uri = URI.create(route.startsWith("/") ? "https://api.music.apple.com" + route : route);
            if (!"https".equals(uri.getScheme()) || !"api.music.apple.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null
                    || !uri.getPath().startsWith("/v1/") || uri.getFragment() != null) {
                throw new IOException("拒绝向非 Apple Music API 地址发送凭据。");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Apple Music 返回了无效的分页地址。", e);
        }
    }

    private static String segment(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }
}
