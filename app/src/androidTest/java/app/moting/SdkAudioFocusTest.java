package app.moting;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory;
import com.apple.android.music.playback.model.PlaybackState;
import com.apple.android.sdk.authentication.TokenProvider;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SdkAudioFocusTest {
    @Test public void realSdkFocusOwnerCanResumeAfterServicePause() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new TokenVault(context).clear();
        TokenVault vault = new TokenVault(context);
        vault.save(new TokenVault.Tokens(vault.read().developer(), "test-only-user"));
        AtomicReference<PlaybackService> service = new AtomicReference<>();
        AtomicReference<MediaPlayerController> controller = new AtomicReference<>();
        AtomicReference<Object> originalEngine = new AtomicReference<>();
        AtomicReference<Field> engineField = new AtomicReference<>();
        AtomicInteger state = new AtomicInteger(PlaybackState.STOPPED);
        Handler main = new Handler(Looper.getMainLooper());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                for (int i = 0; i < 50 && service.get() == null; i++) {
                    scenario.onActivity(activity -> service.set((PlaybackService) field(activity, "playback")));
                    Thread.sleep(50);
                }
                assertNotNull(service.get());
                scenario.onActivity(activity -> {
                    System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "0");
                    System.setProperty("org.bytedeco.javacpp.maxbytes", "0");
                    System.loadLibrary("c++_shared");
                    System.loadLibrary("appleMusicSDK");
                    MediaPlayerController sdk = MediaPlayerControllerFactory.createLocalController(context, new TokenProvider() {
                        @Override public String getDeveloperToken() { return ""; }
                        @Override public String getUserToken() { return ""; }
                    });
                    controller.set(sdk);
                    try {
                        // Only the codec boundary is substituted; SDK command threads and Android audio focus are real.
                        Field engine = sdk.getClass().getDeclaredField("b");
                        engine.setAccessible(true);
                        engineField.set(engine);
                        originalEngine.set(engine.get(sdk));
                        engine.set(sdk, Proxy.newProxyInstance(engine.getType().getClassLoader(),
                                new Class<?>[]{engine.getType()}, (proxy, method, args) -> {
                                    if ((args == null || args.length == 0) && method.getName().equals("b")) {
                                        int previous = state.getAndSet(PlaybackState.PLAYING);
                                        main.post(() -> service.get().onPlaybackStateChanged(sdk, previous, state.get()));
                                        return null;
                                    }
                                    if ((args == null || args.length == 0) && method.getName().equals("c")) {
                                        int previous = state.getAndSet(PlaybackState.PAUSED);
                                        main.post(() -> service.get().onPlaybackStateChanged(sdk, previous, state.get()));
                                        return null;
                                    }
                                    return switch (method.getName()) {
                                        case "e" -> method.getReturnType() == int.class ? state.get() : null;
                                        case "n" -> 1;
                                        case "o" -> List.of();
                                        case "u", "v" -> -1;
                                        case "p" -> state.get() == PlaybackState.PLAYING ? 1f : 0f;
                                        default -> defaultValue(method.getReturnType());
                                    };
                                }));
                        Field player = PlaybackService.class.getDeclaredField("player");
                        player.setAccessible(true);
                        player.set(service.get(), sdk);
                    } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
                    sdk.play();
                });
                awaitState(state, PlaybackState.PLAYING);
                scenario.onActivity(activity -> service.get().pause());
                awaitState(state, PlaybackState.PAUSED);
                scenario.onActivity(activity -> service.get().resume());
                Thread.sleep(1000);
                assertEquals("Resuming must not trigger a second focus owner's pause callback",
                        PlaybackState.PLAYING, state.get());
                scenario.onActivity(activity -> service.get().pause());
                awaitState(state, PlaybackState.PAUSED);
                scenario.onActivity(activity -> service.get().resume());
                Thread.sleep(1000);
                assertEquals("Repeated pause/resume must keep the SDK as the sole focus owner",
                        PlaybackState.PLAYING, state.get());
                AudioManager audio = context.getSystemService(AudioManager.class);
                AudioFocusRequest interruption = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                        .setOnAudioFocusChangeListener(change -> { }).build();
                try {
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(interruption));
                    awaitState(state, PlaybackState.PAUSED);
                } finally {
                    audio.abandonAudioFocusRequest(interruption);
                }
                awaitState(state, PlaybackState.PLAYING);
            } finally {
                scenario.onActivity(activity -> {
                    if (controller.get() != null) {
                        try { engineField.get().set(controller.get(), originalEngine.get()); }
                        catch (IllegalAccessException exception) { throw new AssertionError(exception); }
                        service.get().stopPlayback();
                    }
                });
            }
        } finally {
            context.stopService(new Intent(context, PlaybackService.class));
            new TokenVault(context).clear();
        }
    }

    private static void awaitState(AtomicInteger state, int expected) throws InterruptedException {
        for (int i = 0; i < 50 && state.get() != expected; i++) Thread.sleep(50);
        assertEquals(expected, state.get());
    }
    private static Object field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return null;
    }
}
