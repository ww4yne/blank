package app.moting;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ErrorRenderingTest {
    @Test public void dnsErrorsStayFriendlyAndEveryButtonActuallyPaints() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        new TokenVault(context).clear();
        AtomicInteger requests = new AtomicInteger();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Field api = MainActivity.class.getDeclaredField("api");
                    api.setAccessible(true);
                    api.set(activity, new AppleMusicApi(new TokenVault(context)) {
                        @Override JSONObject get(String route) throws UnknownHostException {
                            requests.incrementAndGet();
                            throw new UnknownHostException("Unable to resolve host api.music.apple.com: No address associated with hostname");
                        }
                    });
                    new TokenVault(context).save(new TokenVault.Tokens("test-only-developer", "test-only-user"));
                } catch (Exception exception) { throw new AssertionError(exception); }
            });
            for (int iteration = 0; iteration < 3; iteration++) {
                scenario.onActivity(activity -> button(activity.getWindow().getDecorView(), "资料库").performClick());
                AtomicBoolean found = new AtomicBoolean();
                for (int i = 0; i < 50 && !found.get(); i++) {
                    scenario.onActivity(activity -> found.set(textContaining(activity.getWindow().getDecorView(), "检查 Wi-Fi") != null));
                    Thread.sleep(50);
                }
                assertTrue("DNS failure needs an actionable Chinese message", found.get());
                instrumentation.waitForIdleSync();
                Thread.sleep(400);
                Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
                assertNotNull(screen);
                scenario.onActivity(activity -> {
                    View root = activity.getWindow().getDecorView();
                    assertTrue(activity.hasWindowFocus());
                    assertNull(textContaining(root, "Unable to resolve"));
                    assertNull(textContaining(root, "api.music.apple.com"));
                    assertButtonPixels(root, screen,
                            List.of("设置", "歌单", "专辑", "歌曲", "推荐", "资料库", "搜索", "播放页面"));
                });
                try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), "layout-friendly-error.png"))) {
                    assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
            }
            int previousRequests = requests.get();
            scenario.onActivity(activity -> textContaining(activity.getWindow().getDecorView(), "点按此提示重试").performClick());
            for (int i = 0; i < 50 && requests.get() <= previousRequests; i++) Thread.sleep(50);
            assertTrue("The friendly error must retain working retry behavior", requests.get() > previousRequests);
        } finally {
            new TokenVault(context).clear();
        }
    }

    static void assertButtonPixels(View root, Bitmap screen, List<String> labels) {
        for (String label : labels) {
            Button button = button(root, label);
            assertNotNull(label, button);
            assertTrue(button instanceof AppCompatButton);
            if (!(button instanceof InkUi.IconButton)) {
                assertEquals("Static text buttons need the reliable software raster path", View.LAYER_TYPE_SOFTWARE, button.getLayerType());
            }
            Rect visible = new Rect();
            assertTrue(label, button.getGlobalVisibleRect(visible));
            assertEquals(label, button.getHeight(), visible.height());
            assertEquals(label, button.getWidth(), visible.width());
            int[] location = new int[2];
            button.getLocationOnScreen(location);
            int margin = InkUi.dp(root.getContext(), 8);
            int ink = 0;
            boolean lightText = button.getCurrentTextColor() == Color.WHITE;
            for (int y = location[1] + margin; y < location[1] + button.getHeight() - margin; y++) {
                for (int x = location[0] + margin; x < location[0] + button.getWidth() - margin; x++) {
                    int pixel = screen.getPixel(x, y);
                    int brightness = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel);
                    if (lightText ? brightness > 660 : brightness < 105) ink++;
                }
            }
            assertTrue(label + " must paint its label, not only have measured bounds", ink > 20);
        }
    }

    private static Button button(View root, String label) {
        if (root instanceof Button button && (label.contentEquals(button.getText())
                || label.contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription()))) return button;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = button(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static TextView textContaining(View root, String value) {
        if (root instanceof TextView text && text.getText().toString().contains(value)) return text;
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = textContaining(group.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }
}
