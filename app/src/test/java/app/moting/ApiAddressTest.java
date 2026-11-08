package app.moting;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class ApiAddressTest {
    @Test public void relativeAndAbsoluteApplePaginationAreAccepted() throws Exception {
        assertEquals("api.music.apple.com", AppleMusicApi.trustedUri("/v1/me/library/songs?offset=25").getHost());
        assertEquals("/v1/me/recommendations", AppleMusicApi.trustedUri(
                "https://api.music.apple.com/v1/me/recommendations").getPath());
    }
    @Test public void credentialsNeverFollowUntrustedAddresses() {
        for (String route : new String[]{
                "https://example.com/v1/me/library/songs",
                "//example.com/v1/me/library/songs",
                "http://api.music.apple.com/v1/me/library/songs",
                "https://api.music.apple.com.attacker.invalid/v1/me",
                "https://api.music.apple.com:8443/v1/me",
                "https://user@api.music.apple.com/v1/me",
                "https://api.music.apple.com/v1/me#fragment",
                "not a url"}) {
            assertThrows(route, IOException.class, () -> AppleMusicApi.trustedUri(route));
        }
    }
}
