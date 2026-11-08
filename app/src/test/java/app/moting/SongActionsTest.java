package app.moting;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class SongActionsTest {
    @Test public void favoritesAndLibraryUseSeparateOfficialEndpointsAndEmptyAcceptedResponses() throws Exception {
        FixtureApi api = new FixtureApi();
        api.favoriteSong("12345");
        assertEquals("/v1/me/favorites?ids%5Bsongs%5D=12345", api.route);
        assertEquals("POST", api.method);
        assertEquals(202, api.status);
        assertNull(api.body);
        api.addSongToLibrary("12345");
        assertEquals("/v1/me/library?ids%5Bsongs%5D=12345", api.route);
        assertEquals(202, api.status);
        assertNull(api.body);
    }

    @Test public void dislikeRequiresAppleToConfirmTheSelectedSongAndNegativeRating() throws Exception {
        FixtureApi api = new FixtureApi();
        api.response = rating("12345", -1);
        api.suggestLess("12345");
        assertEquals("/v1/me/ratings/songs/12345", api.route);
        assertEquals("PUT", api.method);
        assertEquals("ratings", api.body.getString("type"));
        assertEquals(-1, api.body.getJSONObject("attributes").getInt("value"));
        assertEquals(200, api.status);
        for (JSONObject response : new JSONObject[]{new JSONObject(), rating("67890", -1), rating("12345", 1)}) {
            api.response = response;
            assertThrows(IOException.class, () -> api.suggestLess("12345"));
        }
    }

    @Test public void invalidOrLibraryIdentifiersNeverReachTheWriteTransport() {
        FixtureApi api = new FixtureApi();
        for (String id : new String[]{null, "", "i.library", "123&ids[albums]=456", "../123"}) {
            assertThrows(IOException.class, () -> api.favoriteSong(id));
            assertThrows(IOException.class, () -> api.suggestLess(id));
            assertThrows(IOException.class, () -> api.addSongToLibrary(id));
        }
        assertNull(api.route);
    }

    private static JSONObject rating(String id, int value) throws Exception {
        return new JSONObject().put("data", new JSONArray().put(new JSONObject()
                .put("id", id).put("type", "ratings")
                .put("attributes", new JSONObject().put("value", value))));
    }

    private static final class FixtureApi extends AppleMusicApi {
        String route, method;
        JSONObject body, response = new JSONObject();
        int status;
        FixtureApi() { super(null); }
        @Override JSONObject request(String route, String method, JSONObject body, int status) {
            this.route = route;
            this.method = method;
            this.body = body;
            this.status = status;
            return response;
        }
    }
}
