package app.moting;

import static org.junit.Assert.*;

import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.ClipboardManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.model.ErrorConditionException;
import com.apple.android.music.playback.model.MediaPlayerException;
import com.apple.android.music.playback.model.PlaybackState;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PlaybackBufferingTest {
    @Test public void unresolvedQueueTimesOutAfterFortyFiveSecondsWithoutAnSdkError() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PlaybackService service = bound(scenario);
            AtomicInteger state = new AtomicInteger(PlaybackState.STOPPED);
            MediaPlayerController controller = controller(service, state, new AtomicBoolean(true));
            long started = SystemClock.elapsedRealtime();
            scenario.onActivity(activity -> {
                set(service, "player", controller);
                invoke(service, "beginPlaybackWatch");
                service.onPlaybackStateChanged(controller, PlaybackState.PLAYING, PlaybackState.STOPPED);
                assertTrue(watching(service));
                assertTrue(service.error().isEmpty());
            });
            Thread.sleep(1000);
            scenario.onActivity(activity -> assertTrue("A short buffer must not fail prematurely", service.error().isEmpty()));
            AtomicBoolean failed = new AtomicBoolean();
            for (int i = 0; i < 100 && !failed.get(); i++) {
                Thread.sleep(500);
                scenario.onActivity(activity -> failed.set(!service.error().isEmpty()));
            }
            assertTrue("Missing SDK completion/error callbacks must not buffer forever", failed.get());
            assertTrue(SystemClock.elapsedRealtime() - started >= 45000);
            scenario.onActivity(activity -> {
                assertTrue(service.error().contains("45 秒"));
                assertTrue(service.diagnostics().contains("TIMEOUT"));
                assertTrue(service.diagnostics().contains("queue=0 item=false"));
                assertFalse(watching(service));
                service.stopPlayback();
            });
        }
    }

    @Test public void pauseAndProgressCancelWatchAndStaleCallbacksCannotOverwriteDiagnostics() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PlaybackService service = bound(scenario);
            AtomicInteger state = new AtomicInteger(PlaybackState.PLAYING);
            AtomicBoolean buffering = new AtomicBoolean(true);
            MediaPlayerController controller = controller(service, state, buffering);
            scenario.onActivity(activity -> {
                set(service, "player", controller);
                invoke(service, "beginPlaybackWatch");
                service.pause();
                assertFalse(watching(service));
                state.set(PlaybackState.PLAYING);
                invoke(service, "beginPlaybackWatch");
                buffering.set(false);
                set(service, "watchPosition", -1L);
                service.onPlaybackStateUpdated(controller);
                assertFalse(watching(service));
                MediaPlayerException exception = new MediaPlayerException(MediaPlayerException.TYPE_DRM,
                        new ErrorConditionException("secret-token-must-not-be-copied", -4321));
                service.onPlaybackError(controller, exception);
                assertTrue(service.error().contains("解密"));
                String report = service.diagnostics();
                assertTrue(report.contains("ERROR"));
                assertTrue(report.contains("-4321"));
                assertFalse(report.contains("secret-token"));
                assertFalse(watching(service));
                service.onPlaybackError(controller(service, state, buffering), exception);
                assertEquals("Old controller callbacks must not replace the current report", report, service.diagnostics());
                service.stopPlayback();
            });
        }
    }

    @Test public void diagnosticsCanBeCopiedOnTheReaderWithoutUsb() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PlaybackService service = bound(scenario);
            MediaPlayerController controller = controller(service, new AtomicInteger(PlaybackState.STOPPED),
                    new AtomicBoolean(true));
            scenario.onActivity(activity -> {
                set(service, "player", controller);
                service.onPlaybackError(controller, new MediaPlayerException(MediaPlayerException.TYPE_IO,
                        new ErrorConditionException("never-copy-this-token-or-stream-url", -1234)));
                invoke(activity, "showSettings");
                invoke(activity, "showPlaybackDiagnostics");
            });
            instrumentation.waitForIdleSync();
            boolean copied = false;
            for (int i = 0; i < 30 && !copied; i++) {
                AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("复制诊断");
                    if (!nodes.isEmpty()) copied = nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                if (!copied) Thread.sleep(100);
            }
            assertTrue("The on-device dialog must expose a usable copy action", copied);
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                assertNotNull(clipboard.getPrimaryClip());
                String report = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                assertTrue(report.contains("MusicKit 1.1.1"));
                assertTrue(report.contains("ERROR"));
                assertTrue(report.contains("-1234"));
                assertFalse(report.contains("never-copy"));
                service.stopPlayback();
            });
        }
    }

    @Test public void switchingTracksResetsProgressBaselineAndZeroPositionDoesNotCountAsPlayback() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PlaybackService service = bound(scenario);
            MediaPlayerController controller = controller(service, new AtomicInteger(PlaybackState.PLAYING),
                    new AtomicBoolean(false));
            scenario.onActivity(activity -> {
                set(service, "player", controller);
                invoke(service, "beginPlaybackWatch");
                set(service, "watchPosition", 30000L);
                service.onPlaybackStateUpdated(controller);
                assertTrue("Resetting to zero is not evidence that audio has started", watching(service));
                service.onCurrentItemChanged(controller, null, null);
                assertEquals(0L, get(service, "watchPosition"));
                service.onPlaybackStateUpdated(controller);
                assertTrue("Metadata and a zero position must not disable the timeout", watching(service));
                set(service, "watchPosition", -1L);
                service.onPlaybackStateUpdated(controller);
                assertFalse(watching(service));
                service.stopPlayback();
            });
        }
    }

    @Test public void failedControllerCannotBeResumedAndLatePlayingDoesNotHideTheError() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            PlaybackService service = bound(scenario);
            AtomicInteger state = new AtomicInteger(PlaybackState.PLAYING);
            AtomicBoolean buffering = new AtomicBoolean(false);
            MediaPlayerController controller = controller(service, state, buffering);
            scenario.onActivity(activity -> {
                set(service, "player", controller);
                service.onPlaybackError(controller, new MediaPlayerException(MediaPlayerException.TYPE_IO,
                        new ErrorConditionException("do-not-record", -1234)));
                assertTrue((boolean) get(service, "playerFailed"));
                state.set(PlaybackState.PLAYING);
                service.onPlaybackStateChanged(controller, PlaybackState.PAUSED, PlaybackState.PLAYING);
                assertFalse("Late PLAYING must not turn a failed load into success", service.error().isEmpty());
                service.resume();
                assertTrue(service.error().contains("重新载入"));
                assertFalse(watching(service));
                service.stopPlayback();
            });
        }
    }

    private static MediaPlayerController controller(PlaybackService service, AtomicInteger state, AtomicBoolean buffering) {
        return MediaPlayerController.class.cast(Proxy.newProxyInstance(MediaPlayerController.class.getClassLoader(),
                new Class<?>[]{MediaPlayerController.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPlaybackState" -> state.get();
                    case "isBuffering" -> buffering.get();
                    case "getQueueItems" -> List.of();
                    case "pause" -> {
                        int before = state.getAndSet(PlaybackState.PAUSED);
                        service.onPlaybackStateChanged((MediaPlayerController) proxy, before, PlaybackState.PAUSED);
                        yield null;
                    }
                    default -> {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) yield false;
                        if (type == int.class) yield 0;
                        if (type == long.class) yield 0L;
                        if (type == float.class) yield 0f;
                        yield null;
                    }
                }));
    }

    private static PlaybackService bound(ActivityScenario<MainActivity> scenario) throws Exception {
        AtomicReference<PlaybackService> service = new AtomicReference<>();
        for (int i = 0; i < 50 && service.get() == null; i++) {
            scenario.onActivity(activity -> service.set((PlaybackService) get(activity, "playback")));
            Thread.sleep(50);
        }
        assertNotNull(service.get());
        return service.get();
    }
    private static boolean watching(PlaybackService service) {
        return (boolean) get(service, "waitingForPlayback")
                && ((Handler) get(service, "handler")).hasCallbacks((Runnable) get(service, "bufferingTimeout"));
    }
    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void invoke(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
}
