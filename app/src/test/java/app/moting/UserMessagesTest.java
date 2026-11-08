package app.moting;

import static org.junit.Assert.*;

import org.json.JSONException;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;

import javax.net.ssl.SSLHandshakeException;

public final class UserMessagesTest {
    @Test public void dnsAndWrappedNetworkErrorsNeverExposeRawHostnames() {
        for (Exception error : new Exception[]{
                new UnknownHostException("Unable to resolve host api.music.apple.com"),
                new IOException("request failed", new UnknownHostException("api.music.apple.com")),
                new ConnectException("ECONNREFUSED")}) {
            String message = UserMessages.readable(error);
            assertTrue(message.contains("检查 Wi-Fi"));
            assertFalse(message.contains("api.music.apple.com"));
            assertFalse(message.contains("ECONNREFUSED"));
        }
    }

    @Test public void timeoutTlsAndConnectionResetHaveSpecificRecoveryAdvice() {
        assertEquals("连接超时，请稍后重试。", UserMessages.readable(new SocketTimeoutException("Read timed out")));
        assertTrue(UserMessages.readable(new SSLHandshakeException("Trust anchor not found")).contains("设备时间"));
        assertTrue(UserMessages.readable(new SocketException("Connection reset")).contains("连接中断"));
        assertTrue(UserMessages.readable(new GeneralSecurityException("bad key")).contains("重新连接"));
    }

    @Test public void unexpectedTechnicalDetailsAreReplacedButKnownUserMessagesSurvive() {
        assertEquals("操作暂未完成，请稍后重试。", UserMessages.readable(new IOException("native failure details")));
        assertEquals("暂时无法读取服务数据，请稍后重试。", UserMessages.readable(new JSONException("JSONObject missing field")));
        assertEquals("请先连接 Apple Music。", UserMessages.readable(new IOException("请先连接 Apple Music。")));
    }
}
