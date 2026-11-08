package app.moting;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.model.PlaybackState;
import com.apple.android.music.playback.model.PlayerMediaItem;
import com.apple.android.music.playback.model.PlayerQueueItem;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class SongActionsIntegrationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After public void cleanup() throws Exception { new TokenVault(context).clear(); }

    @Test public void writeTransportSendsJsonAndAcceptsEmpty202ButNotAuthenticationErrors() throws Exception {
        TokenVault vault = new TokenVault(context);
        vault.clear();
        vault.save(new TokenVault.Tokens(vault.read().developer(), "test-only-user"));
        AtomicReference<Connection> transport = new AtomicReference<>();
        AppleMusicApi api = new AppleMusicApi(vault) {
            @Override HttpURLConnection openConnection(URI uri) { return transport.get(); }
        };
        Connection accepted = new Connection(202, "");
        transport.set(accepted);
        api.favoriteSong("12345");
        assertEquals("POST", accepted.getRequestMethod());
        assertFalse(accepted.getInstanceFollowRedirects());
        assertTrue(accepted.disconnected);
        Connection rated = new Connection(200,
                "{\"data\":[{\"id\":\"12345\",\"type\":\"ratings\",\"attributes\":{\"value\":-1}}]}");
        transport.set(rated);
        api.suggestLess("12345");
        assertEquals("PUT", rated.getRequestMethod());
        assertEquals("application/json", rated.getRequestProperty("Content-Type"));
        assertEquals(-1, new JSONObject(new String(rated.output.toByteArray(), StandardCharsets.UTF_8))
                .getJSONObject("attributes").getInt("value"));
        assertTrue(rated.disconnected);
        for (int status : new int[]{401, 403, 302, 500}) {
            Connection rejected = new Connection(status, "");
            transport.set(rejected);
            assertThrows(IOException.class, () -> api.favoriteSong("12345"));
            assertTrue(rejected.disconnected);
        }
    }

    @Test public void currentTrackMenuWritesTheCapturedSongAndShowsFailuresInsteadOfSuccess() throws Exception {
        new TokenVault(context).save(new TokenVault.Tokens("test-only-developer", "test-only-user"));
        AtomicReference<String> route = new AtomicReference<>();
        AtomicReference<String> currentId = new AtomicReference<>("12345");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<PlaybackService> service = new AtomicReference<>();
            for (int i = 0; i < 50 && service.get() == null; i++) {
                scenario.onActivity(activity -> service.set((PlaybackService) get(activity, "playback")));
                SystemClock.sleep(50);
            }
            assertNotNull(service.get());
            scenario.onActivity(activity -> {
                PlayerMediaItem item = proxy(PlayerMediaItem.class, (method, args) -> switch (method) {
                    case "getSubscriptionStoreId" -> currentId.get();
                    case "getTitle" -> "操作测试歌曲";
                    case "getArtistName", "getAlbumTitle", "getArtworkUrl" -> "";
                    default -> null;
                });
                PlayerQueueItem queueItem = proxy(PlayerQueueItem.class, (method, args) ->
                        method.equals("getItem") ? item : null);
                MediaPlayerController player = proxy(MediaPlayerController.class, (method, args) -> switch (method) {
                    case "getCurrentItem" -> queueItem;
                    case "getQueueItems" -> List.of();
                    case "getPlaybackState" -> PlaybackState.PAUSED;
                    case "getPlaybackQueueItemCount" -> 1;
                    default -> null;
                });
                set(service.get(), "player", player);
                set(activity, "api", new AppleMusicApi(new TokenVault(context)) {
                    @Override JSONObject request(String path, String method, JSONObject body, int status) throws IOException {
                        route.set(path);
                        if (method.equals("PUT")) throw new IOException("测试反馈被拒绝。");
                        return new JSONObject();
                    }
                });
                service.get().onCurrentItemChanged(player, null, queueItem);
                View actions = (View) get(activity, "songActions");
                assertEquals(View.VISIBLE, actions.getVisibility());
                assertTrue(actions.isEnabled());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(200);
            scenario.onActivity(activity -> {
                View actions = (View) get(activity, "songActions");
                assertTrue(actions.getHeight() >= InkUi.dp(activity, 48));
                actions.performClick();
                currentId.set("67890");
            });
            clickDialog("添加收藏");
            awaitMessage(scenario, "已提交收藏请求");
            assertEquals("The menu must act on the song shown when it opened, not a later song",
                    "/v1/me/favorites?ids%5Bsongs%5D=12345", route.get());
            scenario.onActivity(activity -> ((View) get(activity, "songActions")).performClick());
            clickDialog("减少类似推荐");
            awaitMessage(scenario, "测试反馈被拒绝");
            assertEquals("/v1/me/ratings/songs/67890", route.get());
            scenario.onActivity(activity -> {
                assertFalse(((TextView) get(activity, "message")).getText().toString().contains("已提交减少推荐"));
                assertTrue(((View) get(activity, "songActions")).isEnabled());
                service.get().stopPlayback();
            });
        }
    }

    private static void clickDialog(String text) {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
        for (int i = 0; i < 40; i++) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (node.isVisibleToUser() && !bounds.isEmpty()) {
                        long time = SystemClock.uptimeMillis();
                        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
                                bounds.centerX(), bounds.centerY(), 0);
                        MotionEvent up = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_UP,
                                bounds.centerX(), bounds.centerY(), 0);
                        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        try {
                            assertTrue(instrumentation.getUiAutomation().injectInputEvent(down, true));
                            assertTrue(instrumentation.getUiAutomation().injectInputEvent(up, true));
                        } finally { down.recycle(); up.recycle(); }
                        return;
                    }
                }
            }
            SystemClock.sleep(50);
        }
        fail("Missing actionable menu item: " + text);
    }

    private static void awaitMessage(ActivityScenario<MainActivity> scenario, String text) {
        AtomicReference<String> message = new AtomicReference<>("");
        for (int i = 0; i < 100; i++) {
            scenario.onActivity(activity -> message.set(((TextView) get(activity, "message")).getText().toString()));
            if (message.get().contains(text)) return;
            SystemClock.sleep(50);
        }
        fail("Missing operation result: " + message.get());
    }

    private interface Invocation { Object call(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            Object result = invocation.call(method.getName(), args);
            if (result != null) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == float.class) return 0f;
            return null;
        }));
    }

    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static final class Connection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final int status;
        final byte[] response;
        boolean disconnected;
        Connection(int status, String response) throws Exception {
            super(new URL("https://api.music.apple.com/v1/me/favorites"));
            this.status = status;
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
