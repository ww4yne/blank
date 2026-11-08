package app.moting;

import org.json.JSONException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertThrows;

public class DeveloperTokenTest {
    private static String token(String header, String body) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(body.getBytes(StandardCharsets.UTF_8)) + ".test-signature";
    }
    @Test public void validShapeIsAcceptedWithoutClaimingSignatureVerification() throws Exception {
        DeveloperToken.validate(token("{\"alg\":\"ES256\",\"kid\":\"test\"}",
                "{\"iss\":\"team\",\"iat\":900,\"exp\":2000}"), 1000);
    }
    @Test public void expiredTokenIsRejected() {
        assertThrows(JSONException.class, () -> DeveloperToken.validate(token(
                "{\"alg\":\"ES256\",\"kid\":\"test\"}", "{\"iss\":\"team\",\"exp\":1001}"), 1000));
    }
    @Test public void privateKeyOrPlainTextIsRejected() {
        assertThrows(JSONException.class, () -> DeveloperToken.validate("not-a-jwt", 1000));
    }
    @Test public void incorrectAlgorithmIsRejected() {
        assertThrows(JSONException.class, () -> DeveloperToken.validate(token(
                "{\"alg\":\"none\",\"kid\":\"test\"}", "{\"iss\":\"team\",\"exp\":2000}"), 1000));
    }
    @Test public void invalidBase64IsRejected() {
        assertThrows(JSONException.class, () -> DeveloperToken.validate("!@#.$$$.sig", 1000));
    }
}
