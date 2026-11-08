package app.moting;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory;
import com.apple.android.sdk.authentication.TokenProvider;
import com.apple.android.music.playback.model.PlaybackState;
import com.apple.android.music.playback.model.PlayerMediaItem;
import com.apple.android.music.playback.model.PlayerQueueItem;

import org.json.JSONObject;
import org.json.JSONException;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeSmokeTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After public void clearCredentials() throws Exception {
        context.stopService(new Intent(context, PlaybackService.class));
        new TokenVault(context).clear();
    }

    @Test public void keystoreRoundTripDoesNotStorePlaintext() throws Exception {
        TokenVault vault = new TokenVault(context);
        vault.save(new TokenVault.Tokens("test-developer-not-real", "test-user-not-real"));
        assertEquals("test-developer-not-real", vault.read().developer());
        assertEquals("test-user-not-real", vault.read().user());
        String stored = context.getSharedPreferences("credentials", Context.MODE_PRIVATE).getString("encrypted", "");
        assertFalse(stored.contains("test-developer-not-real"));
        assertFalse(stored.contains("test-user-not-real"));
        assertTrue(new JSONObject(stored).has("iv"));
        vault.clear();
        assertFalse(vault.read().authorized());
    }

    @Test public void modifiedCiphertextFailsExplicitly() throws Exception {
        TokenVault vault = new TokenVault(context);
        vault.save(new TokenVault.Tokens("test-dev", "test-user"));
        var preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE);
        JSONObject stored = new JSONObject(preferences.getString("encrypted", ""));
        String ciphertext = stored.getString("ciphertext");
        stored.put("ciphertext", (ciphertext.charAt(0) == 'A' ? "B" : "A") + ciphertext.substring(1));
        assertTrue(preferences.edit().putString("encrypted", stored.toString()).commit());
        assertThrows(GeneralSecurityException.class, vault::read);
    }

    @Test public void corruptCredentialEncodingFailsWithAnActionableError() throws Exception {
        TokenVault vault = new TokenVault(context);
        vault.save(new TokenVault.Tokens("test-dev", "test-user"));
        var preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE);
        JSONObject stored = new JSONObject(preferences.getString("encrypted", ""));
        stored.put("ciphertext", "A");
        assertTrue(preferences.edit().putString("encrypted", stored.toString()).commit());
        assertThrows(java.io.IOException.class, vault::read);
    }

    @Test public void nativeSdkInitializesAndReleasesWithoutStartingPlayback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "0");
            System.setProperty("org.bytedeco.javacpp.maxbytes", "0");
            System.loadLibrary("c++_shared");
            System.loadLibrary("appleMusicSDK");
            MediaPlayerController controller = MediaPlayerControllerFactory.createLocalController(
                    context, new TokenProvider() {
                        @Override public String getDeveloperToken() { return ""; }
                        @Override public String getUserToken() { return ""; }
                    });
            try {
                assertNotNull(controller);
                assertEquals(0, controller.getPlaybackQueueItemCount());
            } finally { controller.release(); }
        });
    }

    @Test public void navigationAndMissingTokenErrorsRemainUsableAfterRecreation() throws Exception {
        new TokenVault(context).save(new TokenVault.Tokens("", ""));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(findText(activity.getWindow().getDecorView(), "连接 Apple Music"));
                assertNull(findText(activity.getWindow().getDecorView(), "刷新"));
                assertNull(findContaining(activity.getWindow().getDecorView(), "音乐准备好了"));
                click(activity, "设置");
                click(activity, "Apple Music 账号");
                click(activity, "授权 Apple Music");
                assertNotNull(findContaining(activity.getWindow().getDecorView(), "尚未配置"));
                click(activity, "返回");
                click(activity, "返回");
                click(activity, "播放页面");
                assertNotNull(findText(activity.getWindow().getDecorView(), "尚未选择歌曲"));
                click(activity, "播放队列");
                assertNotNull(findContaining(activity.getWindow().getDecorView(), "队列为空"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(findContaining(activity.getWindow().getDecorView(), "队列为空"));
                click(activity, "资料库");
                assertNotNull(findText(activity.getWindow().getDecorView(), "歌单"));
                click(activity, "搜索");
                assertNotNull(findText(activity.getWindow().getDecorView(), "连接 Apple Music"));
            });
        }
    }

    @Test public void personalConfigurationRequiresConsentAndSurvivesSignOut() throws Exception {
        TokenVault vault = new TokenVault(context);
        vault.clear();
        TokenVault.Tokens initial = vault.read();
        assertFalse(initial.authorized());
        assertTrue(initial.user().isEmpty());
        if (!java.util.Arrays.asList(context.getAssets().list("")).contains("developer-token.jwt")) {
            assertTrue(initial.developer().isEmpty());
            return;
        }
        DeveloperToken.validate(initial.developer(), System.currentTimeMillis() / 1000);
        assertTrue(context.getSharedPreferences("credentials", Context.MODE_PRIVATE).getString("encrypted", "").isEmpty());
        vault.save(new TokenVault.Tokens(initial.developer(), "test-user-not-real"));
        vault.clear();
        assertTrue("Signing out must retain the app configuration",
                initial.developer().equals(vault.read().developer()));
        assertTrue(vault.read().user().isEmpty());
    }

    @Test public void configuredHomeStartsOfficialAuthorizationOrExplainsUnavailableApp() throws Exception {
        TokenVault vault = new TokenVault(context);
        vault.clear();
        if (vault.read().developer().isEmpty()) return;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String appProblem = "";
        try {
            if (context.getPackageManager().getPackageInfo("com.apple.android.music", 0).getLongVersionCode() < 680) {
                appProblem = "请先更新官方 Apple Music";
            } else if (context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("musicsdk://applemusic/authenticate-v1")).setPackage("com.apple.android.music"),
                    PackageManager.MATCH_DEFAULT_ONLY) == null) {
                appProblem = "Apple Music 未提供授权入口";
            }
        } catch (PackageManager.NameNotFoundException exception) {
            appProblem = "未安装官方 Apple Music";
        }
        String expectedProblem = appProblem;
        AtomicReference<Intent> started = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                started.set(intent);
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(600);
            capture("pocket-configured-welcome.png");
            instrumentation.addMonitor(monitor);
            scenario.onActivity(activity -> {
                click(activity, "连接 Apple Music");
                if (expectedProblem.isEmpty()) {
                    assertNotNull("The configured connect action must launch the official SDK intent", started.get());
                    assertEquals("com.apple.android.sdk.authentication.SDKUriHandlerActivity",
                            started.get().getComponent().getClassName());
                } else {
                    assertNull(started.get());
                    assertNotNull(findContaining(activity.getWindow().getDecorView(), expectedProblem));
                }
            });
            assertFalse(vault.read().authorized());
        } finally { instrumentation.removeMonitor(monitor); }
    }

    @Test public void officialAuthorizationIntentSkipsThePromotionalStartScreen() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Intent intent = activity.authorizationIntent("test-only-developer");
                assertNotNull(intent.getComponent());
                assertEquals("com.apple.android.sdk.authentication.SDKUriHandlerActivity",
                        intent.getComponent().getClassName());
                assertNull(findText(activity.getWindow().getDecorView(), "刷新"));
            });
        }
    }

    @Test public void cancelledResultsNeverSaveTokensAndSdkErrorsRemainActionable() throws Exception {
        TokenVault vault = new TokenVault(context);
        vault.save(new TokenVault.Tokens("test-developer-not-real", ""));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.onActivityResult(30, Activity.RESULT_CANCELED,
                        new Intent().putExtra("music_user_token", "test-cancelled-user"));
                assertNotNull(findContaining(activity.getWindow().getDecorView(), "授权未完成"));
                activity.onActivityResult(30, Activity.RESULT_CANCELED,
                        new Intent().putExtra("music_user_token_error",
                                com.apple.android.sdk.authentication.TokenError.NO_SUBSCRIPTION.getErrorCode()));
                assertNotNull(findContaining(activity.getWindow().getDecorView(), "此账号没有"));
            });
            assertTrue(vault.read().user().isEmpty());
        }
    }

    @Test public void idleScreenDoesNotRedrawAtTheFifteenSecondTick() throws Exception {
        new TokenVault(context).clear();
        AtomicInteger frames = new AtomicInteger();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Thread.sleep(1500);
            scenario.onActivity(activity -> activity.getWindow().addOnFrameMetricsAvailableListener(
                    (window, metrics, dropped) -> frames.incrementAndGet(), new Handler(Looper.getMainLooper())));
            Thread.sleep(16500);
            assertEquals("An idle e-ink screen must not redraw on the progress timer.", 0, frames.get());
        }
    }

    @Test public void playingClearsOldErrorsAndQueueRowsRemainVisible() throws Exception {
        new TokenVault(context).clear();
        TokenVault vault = new TokenVault(context);
        vault.save(new TokenVault.Tokens(vault.read().developer(), "test-only-user"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<PlaybackService> service = new AtomicReference<>();
            for (int i = 0; i < 50 && service.get() == null; i++) {
                scenario.onActivity(activity -> service.set((PlaybackService) field(activity, "playback")));
                Thread.sleep(100);
            }
            assertNotNull("Playback service must bind", service.get());
            PlayerQueueItem first = queueItem(7L, "测试播放歌曲");
            PlayerQueueItem second = queueItem(8L, "第二首测试歌曲");
            AtomicReference<PlayerQueueItem> current = new AtomicReference<>(first);
            AtomicInteger state = new AtomicInteger(PlaybackState.PLAYING);
            AtomicInteger plays = new AtomicInteger(), pauses = new AtomicInteger(), skips = new AtomicInteger();
            AtomicInteger previousSkips = new AtomicInteger(), shuffle = new AtomicInteger(), repeat = new AtomicInteger();
            AtomicBoolean allowPrevious = new AtomicBoolean();
            AtomicBoolean upcoming = new AtomicBoolean();
            AtomicReference<List<Rect>> controlBounds = new AtomicReference<>();
            MediaPlayerController player = MediaPlayerController.class.cast(Proxy.newProxyInstance(
                    MediaPlayerController.class.getClassLoader(), new Class<?>[]{MediaPlayerController.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCurrentItem" -> current.get();
                        case "getQueueItems" -> upcoming.get() ? List.of(second) : List.of();
                        case "getPlaybackQueueItemCount" -> upcoming.get() ? 2 : 1;
                        case "getPlaybackState" -> state.get();
                        case "getDuration" -> 269000L;
                        case "getCurrentPosition" -> 6000L;
                        case "canSeek" -> true;
                        case "canSkipToNextItem" -> upcoming.get();
                        case "canSkipToPreviousItem" -> allowPrevious.get();
                        case "skipToNextItem" -> { skips.incrementAndGet(); yield null; }
                        case "skipToPreviousItem" -> { previousSkips.incrementAndGet(); yield null; }
                        case "canSetShuffleMode", "canSetRepeatMode" -> true;
                        case "getShuffleMode" -> shuffle.get();
                        case "getRepeatMode" -> repeat.get();
                        case "setShuffleMode" -> { shuffle.set((Integer) args[0]); yield null; }
                        case "setRepeatMode" -> { repeat.set((Integer) args[0]); yield null; }
                        case "pause", "play" -> {
                            int previous = state.get();
                            boolean play = method.getName().equals("play");
                            if (play) plays.incrementAndGet(); else pauses.incrementAndGet();
                            state.set(play ? PlaybackState.PLAYING : PlaybackState.PAUSED);
                            service.get().onPlaybackStateChanged(MediaPlayerController.class.cast(proxy), previous, state.get());
                            yield null;
                        }
                        default -> primitiveDefault(method.getReturnType());
                    }));
            scenario.onActivity(activity -> {
                click(activity, "播放页面");
                Button play = (Button) field(activity, "playerPlay");
                assertFalse("Empty player must not generate a premature resume error", play.isEnabled());
                setField(service.get(), "player", player);
                setField(service.get(), "error", "请先从资料库或搜索结果选择歌曲。");
                service.get().onPlaybackStateChanged(player, PlaybackState.PAUSED, PlaybackState.PLAYING);
                assertTrue(service.get().error().isEmpty());
                assertNull(findContaining(activity.getWindow().getDecorView(), "请先从资料库"));
                assertTrue(play.isEnabled());
                assertEquals("Enabled selected controls must retain white text", Color.WHITE, play.getCurrentTextColor());
                assertNotNull(findText(activity.getWindow().getDecorView(), "测试播放歌曲"));
                assertEquals("暂停", play.getContentDescription());
                assertEquals(0f, play.getElevation(), 0f);
                assertEquals(0f, play.getTranslationZ(), 0f);
                assertNull(play.getForeground());
                android.widget.SeekBar progress = (android.widget.SeekBar) field(activity, "seek");
                assertNull("Seek thumb must not have a theme ripple halo", progress.getBackground());
                assertTrue(progress.getThumb() instanceof android.graphics.drawable.GradientDrawable);
                assertTrue("The SDK may return no upcoming items while the current track is valid", player.getQueueItems().isEmpty());
                play.performClick();
                assertEquals(1, pauses.get());
                assertFalse(service.get().playing());
                assertEquals("播放", play.getContentDescription());
                play.performClick();
                assertEquals("A paused current song must resume even with an empty upcoming queue", 1, plays.get());
                assertTrue(service.get().error().isEmpty());
                assertTrue(service.get().playing());
                assertEquals("暂停", play.getContentDescription());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(600);
            scenario.onActivity(activity -> {
                assertTrue("Player capture must be the focused visible activity", activity.hasWindowFocus());
                View play = (View) field(activity, "playerPlay");
                ViewGroup controls = (ViewGroup) play.getParent();
                assertEquals("All five playback actions must share one row", 5, controls.getChildCount());
                int center = play.getTop() + play.getHeight() / 2;
                int right = 0;
                for (int i = 0; i < controls.getChildCount(); i++) {
                    View control = controls.getChildAt(i);
                    assertEquals("Playback actions must align vertically", center, control.getTop() + control.getHeight() / 2);
                    assertTrue("Playback actions must not overlap", control.getLeft() >= right);
                    assertTrue("Playback actions must fit horizontally", control.getRight() <= controls.getWidth());
                    assertTrue(control.getWidth() >= InkUi.dp(activity, 48));
                    right = control.getRight();
                }
                controlBounds.set(controlBounds(controls));
            });
            Bitmap playerScreen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(playerScreen);
            scenario.onActivity(activity -> ErrorRenderingTest.assertButtonPixels(activity.getWindow().getDecorView(),
                    playerScreen, List.of("设置", "推荐", "资料库", "搜索", "播放页面")));
            capture("layout-playing.png");
            scenario.onActivity(activity -> {
                try { vault.save(new TokenVault.Tokens(vault.read().developer(), "test-only-user")); }
                catch (IOException | JSONException | GeneralSecurityException exception) { throw new AssertionError(exception); }
                setField(activity, "api", new AppleMusicApi(new TokenVault(context)) {
                    @Override MusicData.Page recommendations() {
                        return new MusicData.Page(List.of(
                                new MusicData.Item("r.test", "recommendation-groups", "专属精选", "Apple Music · 测试数据", "", ""),
                                new MusicData.Item("r.new", "recommendation-groups", "新发现", "Apple Music · 测试数据", "", "")), "");
                    }
                    @Override MusicData.Page library(String kind) {
                        return new MusicData.Page(List.of(), "");
                    }
                });
                click(activity, "推荐");
            });
            Thread.sleep(600);
            scenario.onActivity(activity -> {
                assertNotNull(findText(activity.getWindow().getDecorView(), "专属精选"));
                assertEquals(View.VISIBLE, ((View) field(activity, "mini")).getVisibility());
                assertNotNull(findText(activity.getWindow().getDecorView(), "测试播放歌曲"));
                assertEquals("Recommendations must keep every control at the playback-page coordinates",
                        controlBounds.get(), controlBounds((ViewGroup) ((View) field(activity, "miniPlay")).getParent()));
            });
            capture("pocket-browse-playing.png");
            scenario.onActivity(activity -> click(activity, "资料库"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(400);
            scenario.onActivity(activity -> {
                assertEquals("Library pagination and categories must not move the playback controls",
                        controlBounds.get(), controlBounds((ViewGroup) ((View) field(activity, "miniPlay")).getParent()));
                click(activity, "推荐");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(400);
            scenario.onActivity(activity -> {
                upcoming.set(true);
                service.get().onPlaybackQueueChanged(player, List.of(second));
                assertEquals(2, service.get().queue().size());
                assertEquals(first.getPlaybackQueueId(), service.get().queue().get(0).getPlaybackQueueId());
                click(activity, "播放队列");
            });
            Thread.sleep(700);
            scenario.onActivity(activity -> {
                TextView firstRow = findContaining(activity.getWindow().getDecorView(), "正在播放 · 测试播放歌曲");
                TextView secondRow = findContaining(activity.getWindow().getDecorView(), "第二首测试歌曲");
                for (TextView row : List.of(firstRow, secondRow)) {
                    Rect visible = new Rect();
                    assertTrue("Queue rows must have nonzero height", row.getHeight() > 0);
                    assertTrue(row.getGlobalVisibleRect(visible));
                    assertEquals("Queue rows must not be clipped", row.getHeight(), visible.height());
                }
                ViewGroup controls = (ViewGroup) ((View) field(activity, "miniPlay")).getParent();
                assertEquals("The browsing dock needs all five actions", 5, controls.getChildCount());
                int center = controls.getChildAt(0).getTop() + controls.getChildAt(0).getHeight() / 2;
                int right = 0;
                for (int i = 0; i < controls.getChildCount(); i++) {
                    View control = controls.getChildAt(i);
                    Rect visible = new Rect();
                    assertTrue("Dock actions must be fully visible", control.getGlobalVisibleRect(visible));
                    assertEquals(control.getHeight(), visible.height());
                    assertEquals(control.getWidth(), visible.width());
                    assertEquals(center, control.getTop() + control.getHeight() / 2);
                    assertTrue(control.getLeft() >= right);
                    assertTrue(control.getRight() <= controls.getWidth());
                    assertTrue(control.getWidth() >= InkUi.dp(activity, 48));
                    assertTrue(control.getHeight() >= InkUi.dp(activity, 48));
                    right = control.getRight();
                }
            });
            capture("layout-queue.png");
            scenario.onActivity(activity -> {
                ((Button) field(activity, "miniMode")).performClick();
                assertEquals("随机播放", service.get().mode());
                assertEquals("随机播放", ((Button) field(activity, "miniMode")).getContentDescription());
                allowPrevious.set(true);
                service.get().onPlaybackQueueChanged(player, List.of(second));
                ((Button) field(activity, "miniPrevious")).performClick();
                ((Button) field(activity, "miniNext")).performClick();
                assertEquals(1, previousSkips.get());
                assertEquals(1, skips.get());
                allowPrevious.set(false);
                service.get().onPlaybackQueueChanged(player, List.of(second));
            });
            AtomicReference<Notification> notification = new AtomicReference<>();
            scenario.onActivity(activity -> {
                Button mini = (Button) field(activity, "miniPlay");
                mini.performClick();
                assertFalse(service.get().playing());
                assertEquals("播放", mini.getContentDescription());
                for (var active : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                    if (active.getId() == 7) notification.set(active.getNotification());
                }
                assertNotNull(notification.get());
                assertEquals(3, notification.get().actions.length);
                assertEquals(R.drawable.ic_play, notification.get().actions[1].getIcon().getResId());
                assertNull("Compact media notification must not request large artwork", notification.get().getLargeIcon());
            });
            notification.get().actions[1].actionIntent.send();
            Thread.sleep(350);
            scenario.onActivity(activity -> {
                assertTrue("Notification control must use the same working resume path", service.get().playing());
                assertEquals(2, plays.get());
                assertEquals("暂停", ((Button) field(activity, "miniPlay")).getContentDescription());
            });
            var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            Thread.sleep(400);
            capture("layout-notification.png");
            try (var descriptor = automation.executeShellCommand("cmd statusbar collapse");
                 var output = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
                while (output.read() != -1) { }
            }
            Thread.sleep(350);
            scenario.onActivity(activity -> activity.onNewIntent(
                    new Intent(activity.getIntent()).putExtra("openPlayer", true)));
            Thread.sleep(350);
            scenario.onActivity(activity -> assertNotNull(findText(activity.getWindow().getDecorView(), "播放")));
            AtomicReference<View> widget = new AtomicReference<>();
            scenario.onActivity(activity -> {
                current.set(queueItem(7L, "测试歌曲 · 漫长夏日的晚风与远方"));
                service.get().onMetadataUpdated(player, current.get());
                View view = MusicWidget.views(context, 600).apply(context, new android.widget.FrameLayout(context));
                activity.setContentView(view);
                widget.set(view);
                assertEquals("暂停", view.findViewById(R.id.widget_play).getContentDescription());
                assertEquals("测试歌曲 · 漫长夏日的晚风与远方",
                        ((TextView) view.findViewById(R.id.widget_title)).getText().toString());
                assertFalse(view.findViewById(R.id.widget_previous).isEnabled());
                assertTrue(view.findViewById(R.id.widget_next).isEnabled());
            });
            Thread.sleep(350);
            capture("layout-widget-playing.png");
            scenario.onActivity(activity -> widget.get().findViewById(R.id.widget_play).performClick());
            Thread.sleep(350);
            scenario.onActivity(activity -> {
                assertFalse("Widget must pause the shared SDK player", service.get().playing());
                MusicWidget.views(context, 600).reapply(context, widget.get());
                assertEquals("播放", widget.get().findViewById(R.id.widget_play).getContentDescription());
                widget.get().findViewById(R.id.widget_play).performClick();
            });
            Thread.sleep(350);
            scenario.onActivity(activity -> {
                assertTrue("Widget must resume the shared SDK player", service.get().playing());
                assertEquals(3, plays.get());
                MusicWidget.views(context, 600).reapply(context, widget.get());
                assertEquals("暂停", widget.get().findViewById(R.id.widget_play).getContentDescription());
                widget.get().findViewById(R.id.widget_next).performClick();
            });
            Thread.sleep(350);
            assertEquals("Widget next must reach the same SDK controller", 2, skips.get());
        }
    }

    @Test public void launcherUsesTransparentLegacyBitmapInsteadOfBakedFrame() throws Exception {
        var icon = context.getPackageManager().getApplicationIcon(context.getPackageName());
        assertTrue("Hisense-compatible launcher icon must resolve to a legacy bitmap", icon instanceof BitmapDrawable);
        Bitmap bitmap = ((BitmapDrawable) icon).getBitmap();
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)));
        assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.getWidth() - 1, bitmap.getHeight() - 1)));
        boolean blackGlyph = false;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) == 0) continue;
                assertEquals("Icon must contain only a black glyph, not a white background", 0, pixel & 0x00ffffff);
                if (Color.alpha(pixel) == 255) blackGlyph = true;
            }
        }
        assertTrue("Icon must not be empty", blackGlyph);
    }

    private static void capture(String name) throws IOException {
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (FileOutputStream file = new FileOutputStream(new File(target.getCacheDir(), name))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, file));
        }
    }

    private static PlayerQueueItem queueItem(long id, String title) {
        PlayerMediaItem item = PlayerMediaItem.class.cast(Proxy.newProxyInstance(
                PlayerMediaItem.class.getClassLoader(), new Class<?>[]{PlayerMediaItem.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTitle" -> title;
                    case "getArtistName" -> "测试歌手";
                    case "getAlbumTitle" -> "测试专辑";
                    case "getSubscriptionStoreId" -> "test-" + id;
                    default -> method.getReturnType() == String.class ? "" : primitiveDefault(method.getReturnType());
                }));
        return PlayerQueueItem.class.cast(Proxy.newProxyInstance(
                PlayerQueueItem.class.getClassLoader(), new Class<?>[]{PlayerQueueItem.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> item;
                    case "getPlaybackQueueId" -> id;
                    default -> primitiveDefault(method.getReturnType());
                }));
    }

    private static List<Rect> controlBounds(ViewGroup controls) {
        List<Rect> bounds = new java.util.ArrayList<>();
        for (int i = 0; i < controls.getChildCount(); i++) {
            View control = controls.getChildAt(i);
            Rect visible = new Rect();
            assertTrue(control.getGlobalVisibleRect(visible));
            assertEquals(control.getHeight(), visible.height());
            assertEquals(control.getWidth(), visible.width());
            bounds.add(visible);
        }
        return bounds;
    }
    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return null;
    }
    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static void click(MainActivity activity, String label) {
        Button button = findButton(activity.getWindow().getDecorView(), label);
        assertNotNull("Missing button: " + label, button);
        assertTrue(button.isEnabled());
        button.performClick();
    }
    private static Button findButton(View view, String value) {
        if (view instanceof Button button && (button.getText().toString().equals(value)
                || value.contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription()))) return button;
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                Button result = findButton(group.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static TextView findText(View view, String value) {
        if (view instanceof TextView text && text.getText().toString().equals(value)) return text;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findText(group.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static TextView findContaining(View view, String value) {
        if (view instanceof TextView text && text.getText().toString().contains(value)) return text;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findContaining(group.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }
}
