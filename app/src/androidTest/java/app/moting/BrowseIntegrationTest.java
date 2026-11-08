package app.moting;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowseIntegrationTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After public void cleanup() throws Exception { new TokenVault(context).clear(); }

    @Test public void populatedScreensHaveVisibleRowsAndUnclippedControls() throws Exception {
        new TokenVault(context).clear();
        FixtureApi api = new FixtureApi(context);
        api.recommendationCount = 175;
        api.trackCount = 100;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("api");
                    field.setAccessible(true);
                    field.set(activity, api);
                    new TokenVault(context).save(new TokenVault.Tokens("test-only-developer", "test-only-user"));
                } catch (ReflectiveOperationException | IOException | JSONException | java.security.GeneralSecurityException e) {
                    throw new AssertionError(e);
                }
                click(activity, "推荐");
            });
            awaitText(scenario, "专属精选");
            capture("layout-recommendation-groups.png");
            scenario.onActivity(activity -> openRow(activity, "专属精选"));
            awaitText(scenario, "爱不释耳");
            capture("layout-recommendations.png");
            assertVisibleRows(scenario, "爱不释耳");
            int beforeSettings = api.requests.size();
            scenario.onActivity(activity -> { click(activity, "设置"); click(activity, "返回"); });
            awaitText(scenario, "爱不释耳");
            assertEquals("Leaving settings must retain the opened recommendation group without refetching",
                    beforeSettings, api.requests.size());
            scenario.onActivity(activity -> click(activity, "资料库"));
            awaitText(scenario, "测试歌单 1");
            scenario.onActivity(activity -> click(activity, "专辑"));
            awaitText(scenario, "资料库专辑 1");
            capture("layout-albums.png");
            assertVisibleRows(scenario, "资料库专辑 1");
            scenario.onActivity(activity -> click(activity, "歌曲"));
            awaitText(scenario, "资料库歌曲 1");
            capture("layout-songs.png");
            assertVisibleRows(scenario, "资料库歌曲 1");
            scenario.onActivity(activity -> click(activity, "歌单"));
            awaitText(scenario, "测试歌单 1");
            scenario.onActivity(activity -> ((View) findText(activity.getWindow().getDecorView(),
                    "测试歌单 1").getParent().getParent()).performClick());
            awaitText(scenario, "测试曲目 1");
            capture("layout-tracks.png");
            assertVisibleRows(scenario, "测试曲目 1");
            scenario.onActivity(activity -> click(activity, "搜索"));
            capture("layout-search.png");
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                for (String label : List.of("歌曲", "专辑", "歌单")) {
                    assertFullyVisible(findButton(root, label), "Search category " + label);
                }
            });
        }
    }

    private static void assertVisibleRows(ActivityScenario<MainActivity> scenario, String firstTitle) {
        scenario.onActivity(activity -> {
            View title = findText(activity.getWindow().getDecorView(), firstTitle);
            assertNull(findContaining(activity.getWindow().getDecorView(), "已加载"));
            assertFullyVisible(title, firstTitle);
            ViewGroup rows = (ViewGroup) title.getParent().getParent().getParent();
            int visibleRows = 0;
            for (int i = 0; i < rows.getChildCount(); i++) {
                View row = rows.getChildAt(i);
                if (row instanceof ViewGroup) {
                    assertFullyVisible(row, "Loaded row " + i);
                    float scale = Math.max(1f, activity.getResources().getConfiguration().fontScale);
                    assertTrue("Rows must remain compact rather than stretching to fill the page",
                            row.getHeight() <= InkUi.dp(activity, 68 * scale));
                    visibleRows++;
                }
            }
            assertTrue("Loaded screens need visible rows, not only a counter", visibleRows > 0);
            assertFullyVisible(findButton(activity.getWindow().getDecorView(), "下一页"), "Next page");
        });
    }

    private static void assertFullyVisible(View view, String label) {
        assertNotNull(label, view);
        Rect visible = new Rect();
        assertTrue(label + " must have a nonzero measured height", view.getHeight() > 0);
        assertTrue(label + " must be on screen", view.getGlobalVisibleRect(visible));
        assertEquals(label + " must not be clipped vertically", view.getHeight(), visible.height());
        assertEquals(label + " must not be clipped horizontally", view.getWidth(), visible.width());
    }

    @Test public void recommendationsLibraryTracksAndSearchUseRealScreenCodeWithFixtureTransport() throws Exception {
        new TokenVault(context).clear();
        FixtureApi api = new FixtureApi(context);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("api");
                    field.setAccessible(true);
                    field.set(activity, api);
                    new TokenVault(context).save(new TokenVault.Tokens("test-only-developer", "test-only-user"));
                } catch (ReflectiveOperationException | IOException | JSONException | java.security.GeneralSecurityException e) {
                    throw new AssertionError(e);
                }
                click(activity, "推荐");
            });
            awaitText(scenario, "专属精选");
            capture("fixture-recommendations.png");
            scenario.onActivity(activity -> openRow(activity, "专属精选"));
            awaitText(scenario, "爱不释耳");
            scenario.onActivity(activity -> {
                View card = findText(activity.getWindow().getDecorView(), "爱不释耳");
                assertNotNull(card);
                View row = (View) card.getParent().getParent();
                assertEquals(((View) row.getParent()).getPaddingTop(), row.getTop());
                assertTrue("A short page must not stretch a single row across the screen",
                        row.getHeight() > 0 && row.getHeight() <= ((View) row.getParent()).getHeight() / 2);
                row.performClick();
            });
            awaitText(scenario, "测试曲目 1");
            scenario.onActivity(activity -> assertNotNull(findButton(activity.getWindow().getDecorView(), "播放全部")));
            capture("fixture-tracks.png");
            scenario.onActivity(activity -> click(activity, "资料库"));
            awaitText(scenario, "测试歌单 1");
            for (int i = 0; i < 12 && !api.requests.stream().anyMatch(route -> route.contains("offset=25")); i++) {
                scenario.onActivity(activity -> click(activity, "下一页"));
                SystemClock.sleep(150);
            }
            awaitText(scenario, "测试歌单 26");
            assertTrue(api.requests.stream().anyMatch(route -> route.contains("offset=25")));
            scenario.onActivity(activity -> {
                click(activity, "搜索");
                EditText input = findEdit(activity.getWindow().getDecorView());
                assertNotNull(input);
                input.setText("测试");
                View submit = findButton((View) input.getParent(), "搜索");
                assertNotNull(submit);
                submit.performClick();
            });
            awaitText(scenario, "搜索测试歌曲");
            assertTrue(api.requests.stream().anyMatch(route -> route.contains("term=%E6%B5%8B%E8%AF%95")));
            scenario.onActivity(activity -> {
                api.failRecommendations = true;
                click(activity, "推荐");
            });
            awaitContaining(scenario, "测试：连接中断");
            scenario.onActivity(activity -> {
                api.failRecommendations = false;
                View retry = findContaining(activity.getWindow().getDecorView(), "点按此提示重试");
                assertNotNull(retry);
                retry.performClick();
            });
            awaitText(scenario, "专属精选");
            scenario.onActivity(activity -> {
                click(activity, "设置");
                click(activity, "Apple Music 账号");
                click(activity, "检查账号连接");
            });
            awaitContaining(scenario, "账号连接正常");
            scenario.onActivity(activity -> {
                api.failStorefront = true;
                click(activity, "检查账号连接");
            });
            awaitContaining(scenario, "账号连接未通过");
            scenario.onActivity(activity -> {
                api.failStorefront = false;
                click(activity, "检查账号连接");
            });
            awaitContaining(scenario, "账号连接正常");
            scenario.onActivity(activity -> activity.onActivityResult(30, android.app.Activity.RESULT_OK,
                    new android.content.Intent().putExtra("music_user_token", "test-user-callback")));
            awaitText(scenario, "专属精选");
            assertTrue("Successful official SDK results must be saved before validating the account",
                    new TokenVault(context).read().user().equals("test-user-callback"));
        }
    }

    @Test public void recommendationGroupsAndContentsPaginateIndependently() throws Exception {
        new TokenVault(context).clear();
        FixtureApi api = new FixtureApi(context);
        api.paginateRecommendations = true;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("api");
                    field.setAccessible(true);
                    field.set(activity, api);
                    new TokenVault(context).save(new TokenVault.Tokens("test-only-developer", "test-only-user"));
                } catch (ReflectiveOperationException | IOException | JSONException | java.security.GeneralSecurityException e) {
                    throw new AssertionError(e);
                }
                click(activity, "推荐");
            });
            awaitText(scenario, "专属精选");
            scenario.onActivity(activity -> click(activity, "下一页"));
            awaitText(scenario, "更多精选");
            scenario.onActivity(activity -> openRow(activity, "更多精选"));
            awaitText(scenario, "新增分类歌单");
            scenario.onActivity(activity -> {
                click(activity, "返回");
                openRow(activity, "专属精选");
            });
            awaitText(scenario, "爱不释耳");
            scenario.onActivity(activity -> {
                assertNull("Recommendation sections are not playable playlist containers",
                        findButton(activity.getWindow().getDecorView(), "播放全部"));
                click(activity, "下一页");
            });
            awaitText(scenario, "追加推荐歌单");
            awaitText(scenario, "追加推荐分类");
            scenario.onActivity(activity -> openRow(activity, "追加推荐分类"));
            awaitText(scenario, "追加分类专辑");
            assertTrue(api.requests.stream().anyMatch(route -> route.equals("/v1/me/recommendations?offset=10")));
            assertTrue(api.requests.stream().anyMatch(route -> route.equals("/v1/me/recommendations/r/contents?offset=10")));
        }
    }

    private static final class FixtureApi extends AppleMusicApi {
        final List<String> requests = new CopyOnWriteArrayList<>();
        volatile boolean failRecommendations;
        volatile boolean failStorefront;
        int recommendationCount = 1;
        int trackCount = 8;
        boolean paginateRecommendations;
        FixtureApi(Context context) { super(new TokenVault(context)); }
        @Override JSONObject get(String route) throws IOException, JSONException {
            requests.add(route);
            if (route.contains("/recommendations/r/contents")) {
                return new JSONObject().put("data", new JSONArray().put(
                        resource("pl.extra", "playlists", "追加推荐歌单", "Apple Music · 测试数据"))
                        .put(group("r.extra", "追加推荐分类", new JSONArray().put(
                                resource("a.extra", "albums", "追加分类专辑", "测试歌手")), "")));
            }
            if (route.contains("/me/recommendations")) {
                if (failRecommendations) throw new IOException("测试：连接中断");
                JSONObject playlist = resource("pl.test", "playlists", "爱不释耳", "Apple Music · 测试数据");
                JSONArray contents = new JSONArray().put(playlist);
                for (int i = 2; i <= recommendationCount; i++) {
                    contents.put(resource("pl.test" + i, "playlists", "推荐歌单 " + i, "Apple Music · 测试数据"));
                }
                if (route.contains("offset=10")) {
                    return new JSONObject().put("data", new JSONArray().put(group("r3", "更多精选",
                            new JSONArray().put(resource("pl.new", "playlists", "新增分类歌单", "Apple Music · 测试数据")), "")));
                }
                JSONObject response = new JSONObject().put("data", new JSONArray()
                        .put(group("r", "专属精选", contents, paginateRecommendations
                                ? "/v1/me/recommendations/r/contents?offset=10" : ""))
                        .put(group("r2", "新发现", new JSONArray().put(
                                resource("a.test", "albums", "推荐测试专辑", "测试歌手")), "")));
                if (paginateRecommendations) response.put("next", "/v1/me/recommendations?offset=10");
                return response;
            }
            if (route.contains("/tracks")) {
                JSONArray songs = new JSONArray();
                for (int i = 1; i <= trackCount; i++) songs.put(resource("test-" + i, "songs", "测试曲目 " + i, "测试歌手"));
                return new JSONObject().put("data", songs);
            }
            if (route.contains("/me/library/playlists")) {
                boolean last = route.contains("offset=25");
                JSONArray playlists = new JSONArray();
                for (int i = last ? 26 : 1; i <= (last ? 26 : 25); i++) {
                    playlists.put(resource("p." + i, "library-playlists", "测试歌单 " + i, "私人资料库 · 测试数据"));
                }
                JSONObject response = new JSONObject().put("data", playlists);
                if (!last) response.put("next", "/v1/me/library/playlists?offset=25");
                return response;
            }
            if (route.contains("/me/library/albums") || route.contains("/me/library/songs")) {
                boolean albums = route.contains("/albums");
                JSONArray data = new JSONArray();
                for (int i = 1; i <= 25; i++) {
                    data.put(resource("l." + i, albums ? "library-albums" : "library-songs",
                            (albums ? "资料库专辑 " : "资料库歌曲 ") + i, "测试歌手"));
                }
                return new JSONObject().put("data", data);
            }
            if (route.contains("/me/storefront")) {
                if (failStorefront) throw new IOException("测试：用户授权失效");
                return new JSONObject().put("data", new JSONArray().put(new JSONObject().put("id", "cn")));
            }
            if (route.contains("/search")) {
                return new JSONObject().put("results", new JSONObject().put("songs",
                        new JSONObject().put("data", new JSONArray().put(
                                resource("test-search", "songs", "搜索测试歌曲", "测试歌手")))));
            }
            throw new IOException("Unexpected fixture route: " + route);
        }
        private static JSONObject resource(String id, String type, String title, String subtitle) throws JSONException {
            return new JSONObject().put("id", id).put("type", type).put("attributes",
                    new JSONObject().put("name", title).put("artistName", subtitle)
                            .put("playParams", new JSONObject().put("id", id)));
        }
        private static JSONObject group(String id, String title, JSONArray contents, String next) throws JSONException {
            JSONObject relationship = new JSONObject().put("data", contents);
            if (!next.isEmpty()) relationship.put("next", next);
            return new JSONObject().put("id", id).put("type", "personal-recommendation")
                    .put("attributes", new JSONObject()
                            .put("title", new JSONObject().put("stringForDisplay", title))
                            .put("reason", new JSONObject().put("stringForDisplay", "Apple Music · 测试数据")))
                    .put("relationships", new JSONObject().put("contents", relationship));
        }
    }

    private static void capture(String name) throws IOException {
        SystemClock.sleep(300);
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (FileOutputStream file = new FileOutputStream(new File(target.getCacheDir(), name))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, file));
        }
    }
    private static void awaitText(ActivityScenario<MainActivity> scenario, String value) {
        await(scenario, value, false);
    }
    private static void awaitContaining(ActivityScenario<MainActivity> scenario, String value) {
        await(scenario, value, true);
    }
    private static void await(ActivityScenario<MainActivity> scenario, String value, boolean contains) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AtomicBoolean found = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> found.set((contains
                    ? findContaining(activity.getWindow().getDecorView(), value)
                    : findText(activity.getWindow().getDecorView(), value)) != null));
            if (found.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        StringBuilder diagnostic = new StringBuilder();
        scenario.onActivity(activity -> {
            for (String name : List.of("page", "pageSize", "items", "loading", "next")) {
                try {
                    Field field = MainActivity.class.getDeclaredField(name);
                    field.setAccessible(true);
                    Object result = field.get(activity);
                    diagnostic.append(name).append("=").append(result instanceof List<?> list ? list.size() : result).append("; ");
                } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
            }
        });
        fail("Screen did not show: " + value + " [" + diagnostic + "]");
    }
    private static void click(MainActivity activity, String label) {
        View button = findButton(activity.getWindow().getDecorView(), label);
        assertNotNull("Missing button: " + label, button);
        assertTrue("Disabled button: " + label, button.isEnabled());
        button.performClick();
    }
    private static void openRow(MainActivity activity, String title) {
        View text = findText(activity.getWindow().getDecorView(), title);
        assertNotNull(title, text);
        ((View) text.getParent().getParent()).performClick();
    }
    private static View findButton(View view, String label) {
        if (view instanceof Button button && (button.getText().toString().equals(label)
                || label.contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription()))) return view;
        return children(view, child -> findButton(child, label));
    }
    private static View findText(View view, String value) {
        if (view instanceof TextView text && text.getText().toString().equals(value)) return view;
        return children(view, child -> findText(child, value));
    }
    private static View findContaining(View view, String value) {
        if (view instanceof TextView text && text.getText().toString().contains(value)) return view;
        return children(view, child -> findContaining(child, value));
    }
    private static EditText findEdit(View view) {
        if (view instanceof EditText edit) return edit;
        return (EditText) children(view, BrowseIntegrationTest::findEdit);
    }
    private interface Find { View apply(View view); }
    private static View children(View view, Find find) {
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View match = find.apply(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }
}
