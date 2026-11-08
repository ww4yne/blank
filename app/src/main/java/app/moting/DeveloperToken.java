package app.moting;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class DeveloperToken {
    static long validate(String token, long nowSeconds) throws JSONException {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[2].isBlank()) {
            throw new JSONException("请输入签名后的 Developer Token（JWT），不是 .p8 私钥。");
        }
        try {
            JSONObject header = decode(parts[0]);
            JSONObject payload = decode(parts[1]);
            if (!header.optString("alg").equals("ES256") || header.optString("kid").isBlank()
                    || payload.optString("iss").isBlank()) {
                throw new JSONException("Developer Token 必须使用 ES256，并包含 kid 与 iss。");
            }
            if (payload.optLong("exp") <= nowSeconds + 60) {
                throw new JSONException("Developer Token 已过期或即将过期，请重新签发。");
            }
            if (payload.optLong("iat") > nowSeconds + 300) {
                throw new JSONException("Token 签发时间晚于设备时间，请检查系统时钟。");
            }
            return payload.getLong("exp");
        } catch (IllegalArgumentException e) {
            throw new JSONException("Developer Token 的 Base64URL 编码无效。");
        }
    }

    private static JSONObject decode(String part) throws JSONException {
        return new JSONObject(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
    }
}
