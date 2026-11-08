package app.moting;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.model.MediaContainerType;
import com.apple.android.music.playback.model.MediaItemType;
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider;
import com.apple.android.music.playback.queue.PlaybackQueueItemProvider;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PlaybackListTest {
    @Test public void clickingLovePlaylistTrackKeepsWholeContainerAndOriginalIndex() throws Exception {
        try (Harness test = new Harness()) {
            List<MusicData.Item> tracks = new ArrayList<>();
            for (int i = 0; i < 100; i++) tracks.add(song(i));
            test.show(new MusicData.Item("pl.test", "playlists", "爱不释耳", "", "pl.test", ""),
                    tracks, 37, "/v1/catalog/cn/playlists/pl.test/tracks?offset=100");
            test.click(tracks.get(37));
            assertArrayEquals(parcel(new CatalogPlaybackQueueItemProvider.Builder()
                            .containers(MediaContainerType.PLAYLIST, "pl.test").startItemIndex(37).build()),
                    parcel(test.prepared()));
            assertEquals("MusicKit must load the whole container, not just the currently loaded API page", 0, test.requests.get());
        }
    }

    @Test public void recommendationSongsKeepTheirGroupAndFetchTheNextApiPage() throws Exception {
        try (Harness test = new Harness()) {
            test.nextPage = new MusicData.Page(List.of(song(2), song(3)), "");
            test.show(new MusicData.Item("group", "recommendation-groups", "推荐歌曲", "", "", ""),
                    List.of(song(0), new MusicData.Item("album", "albums", "专辑", "", "album", ""), song(1)),
                    2, "/v1/me/recommendations/group/contents?offset=3");
            test.click(song(1));
            assertArrayEquals(parcel(new CatalogPlaybackQueueItemProvider.Builder()
                            .items(MediaItemType.SONG, "1000", "1001", "1002", "1003").startItemIndex(1).build()),
                    parcel(test.prepared()));
            assertEquals(1, test.requests.get());
        }
    }

    @Test public void libraryPlaylistUsesAllPagesAndStartsFromTheSelectedOccurrence() throws Exception {
        try (Harness test = new Harness()) {
            test.nextPage = new MusicData.Page(List.of(song(3), song(4)), "");
            test.show(new MusicData.Item("p.test", "library-playlists", "个人歌单", "", "", ""),
                    List.of(song(0), song(1), song(0)), 2, "/v1/me/library/playlists/p.test/tracks?offset=3");
            test.scenario.onActivity(activity -> {
                ViewGroup rows = (ViewGroup) field(activity, "rows");
                int page = (int) field(activity, "page"), size = (int) field(activity, "pageSize");
                rows.getChildAt((2 - page * size) * 2).performClick();
            });
            assertArrayEquals(parcel(new CatalogPlaybackQueueItemProvider.Builder()
                            .items(MediaItemType.SONG, "1000", "1001", "1000", "1003", "1004").startItemIndex(2).build()),
                    parcel(test.prepared()));
            assertEquals(1, test.requests.get());
        }
    }

    @Test public void repeatedPaginationFailsExplicitlyWithoutStartingAPartialQueue() throws Exception {
        try (Harness test = new Harness()) {
            String route = "/v1/me/recommendations/group/contents?offset=2";
            test.nextPage = new MusicData.Page(List.of(song(2)), route);
            test.show(new MusicData.Item("group", "recommendation-groups", "推荐歌曲", "", "", ""),
                    List.of(song(0), song(1)), 1, route);
            test.click(song(1));
            AtomicReference<String> message = new AtomicReference<>("");
            for (int i = 0; i < 60 && !message.get().contains("重复的分页"); i++) {
                test.scenario.onActivity(activity -> message.set(((TextView) field(activity, "message")).getText().toString()));
                Thread.sleep(50);
            }
            assertTrue(message.get(), message.get().contains("重复的分页"));
            assertNull(test.provider.get());
        }
    }

    private static MusicData.Item song(int index) {
        return new MusicData.Item("s." + index, "songs", "队列歌曲 " + index, "测试歌手", "" + (1000 + index), "");
    }
    private static byte[] parcel(PlaybackQueueItemProvider provider) {
        Parcel parcel = Parcel.obtain();
        try { provider.writeToParcel(parcel, 0); return parcel.marshall(); }
        finally { parcel.recycle(); }
    }

    private static final class Harness implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final ActivityScenario<MainActivity> scenario;
        final AtomicReference<PlaybackQueueItemProvider> provider = new AtomicReference<>();
        final AtomicInteger requests = new AtomicInteger();
        MusicData.Page nextPage = new MusicData.Page(List.of(), "");
        PlaybackService service;

        Harness() throws Exception {
            TokenVault vault = new TokenVault(context);
            vault.clear();
            String developer = vault.read().developer();
            scenario = ActivityScenario.launch(MainActivity.class);
            AtomicReference<PlaybackService> connected = new AtomicReference<>();
            for (int i = 0; i < 50 && connected.get() == null; i++) {
                scenario.onActivity(activity -> connected.set((PlaybackService) field(activity, "playback")));
                Thread.sleep(50);
            }
            service = connected.get();
            assertNotNull(service);
            scenario.onActivity(activity -> {
                MediaPlayerController controller = (MediaPlayerController) Proxy.newProxyInstance(
                        MediaPlayerController.class.getClassLoader(), new Class<?>[]{MediaPlayerController.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("prepare")) { provider.set((PlaybackQueueItemProvider) args[0]); return null; }
                            if (method.getName().equals("getQueueItems")) return List.of();
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            return null;
                        });
                set(service, "player", controller);
                set(activity, "api", new AppleMusicApi(new TokenVault(context)) {
                    @Override MusicData.Page recommendations() { return new MusicData.Page(List.of(), ""); }
                    @Override MusicData.Page page(String route) throws IOException {
                        requests.incrementAndGet();
                        if (!route.startsWith("/v1/")) throw new IOException("Unexpected fixture route");
                        return nextPage;
                    }
                    @Override MusicData.Page trackPage(String route) throws IOException { return page(route); }
                });
                try { vault.save(new TokenVault.Tokens(developer, "test-only-user")); }
                catch (IOException | org.json.JSONException | java.security.GeneralSecurityException exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        @SuppressWarnings("unchecked")
        void show(MusicData.Item container, List<MusicData.Item> songs, int selected, String next) {
            scenario.onActivity(activity -> {
                set(activity, "browsingContainer", container);
                set(activity, "browseTitle", container.title());
                set(activity, "screen", "推荐");
                List<MusicData.Item> items = (List<MusicData.Item>) field(activity, "items");
                items.clear();
                items.addAll(songs);
                set(activity, "pageAnchor", selected);
                set(activity, "next", next);
                invoke(activity, "browseLayout");
            });
        }
        void click(MusicData.Item selected) throws Exception {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View title = text(activity.getWindow().getDecorView(), selected.title());
                assertNotNull(selected.title(), title);
                ((View) title.getParent().getParent()).performClick();
            });
        }
        PlaybackQueueItemProvider prepared() throws Exception {
            for (int i = 0; i < 100 && provider.get() == null; i++) Thread.sleep(50);
            assertNotNull("Tapping a row must dispatch the complete SDK queue provider", provider.get());
            return provider.get();
        }
        @Override public void close() throws Exception {
            scenario.onActivity(activity -> service.stopPlayback());
            scenario.close();
            context.stopService(new Intent(context, PlaybackService.class));
            new TokenVault(context).clear();
        }
    }

    private static View text(View view, String label) {
        if (view instanceof TextView text && label.contentEquals(text.getText())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = text(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void invoke(Object target, String name) {
        try { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
}
