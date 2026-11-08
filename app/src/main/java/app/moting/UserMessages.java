package app.moting;

import org.json.JSONException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;

import javax.net.ssl.SSLException;

final class UserMessages {
    private UserMessages() {}

    static String readable(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof ConnectException) {
                return "暂时连不上 Apple Music，请检查 Wi-Fi 或换个网络后重试。";
            }
            if (cause instanceof SocketTimeoutException) return "连接超时，请稍后重试。";
            if (cause instanceof SSLException) return "无法建立安全连接，请检查设备时间或网络。";
            if (cause instanceof SocketException) return "网络连接中断，请检查网络后重试。";
            if (cause instanceof GeneralSecurityException) return "无法读取账号凭据，请重新连接 Apple Music。";
        }
        String detail = exception.getMessage();
        if (detail != null && detail.codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN)) {
            return detail;
        }
        if (exception instanceof JSONException) return "暂时无法读取服务数据，请稍后重试。";
        return "操作暂未完成，请稍后重试。";
    }
}
