package app.moting;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PocketUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @After public void cleanup() throws Exception {
        context.stopService(new Intent(context, PlaybackService.class));
        new TokenVault(context).clear();
    }

    @Test public void authorizedHomeIsPlaybackFirstWithFlatNavigationAndCircularPrimary() throws Exception {
        new TokenVault(context).save(new TokenVault.Tokens("test-only-developer", "test-only-user"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                View root = activity.getWindow().getDecorView();
                assertEquals("播放", field(activity, "screen"));
                assertNotNull(findText(root, "播放"));
                assertNotNull(findButton(root, "去推荐找歌"));
                Button nav = findButton(root, "播放页面");
                assertEquals(Color.BLACK, nav.getCurrentTextColor());
                assertEquals(3, ((ViewGroup) nav.getParent()).getChildCount());
                for (String label : List.of("搜索", "设置")) {
                    assertTrue(findButton(root, label) instanceof InkUi.IconButton);
                    assertNull(findButton(root, label).getForeground());
                    assertEquals(0f, findButton(root, label).getElevation(), 0f);
                }
                Button play = (Button) field(activity, "playerPlay");
                assertFalse(play.isEnabled());
                assertEquals(GradientDrawable.OVAL, ((GradientDrawable) play.getBackground()).getShape());
                assertTrue(((View) field(activity, "seek")).getHeight() >= InkUi.dp(activity, 48));
                View cover = (View) field(activity, "cover");
                View body = (View) field(activity, "body");
                int[] art = new int[2], content = new int[2];
                cover.getLocationOnScreen(art);
                body.getLocationOnScreen(content);
                assertTrue("The centered cover must be larger than the old compact thumbnail",
                        cover.getWidth() >= InkUi.dp(activity, 128));
                assertEquals(content[0] + body.getWidth() / 2, art[0] + cover.getWidth() / 2);
                assertNull("Playback-first startup must not issue a browse request", findText(root, "正在加载…"));
            });
        }
    }

    @Test public void settingsHaveThreeEntrancesSecureSubpagesAndRestorableHierarchy() throws Exception {
        new TokenVault(context).clear();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                click(activity, "设置");
                View root = activity.getWindow().getDecorView();
                ViewGroup form = (ViewGroup) findButton(root, "Apple Music 账号").getParent();
                int rows = 0;
                for (int i = 0; i < form.getChildCount(); i++) if (form.getChildAt(i) instanceof Button) rows++;
                assertEquals(3, rows);
                assertNull(findButton(root, "授权 Apple Music"));
                assertNull(findButton(root, "退出账号"));
                assertNull(findButton(root, "开发者配置（高级）"));
                assertNull(findEdit(root));
                for (String label : List.of("Apple Music 账号", "播放与后台", "更多设置")) {
                    Button button = findButton(root, label);
                    assertEquals(View.LAYER_TYPE_SOFTWARE, button.getLayerType());
                    assertEquals(Color.BLACK, button.getCurrentTextColor());
                }
                assertSecure(activity);
            });
            captureSettings(scenario, "pocket-settings.png");
            scenario.onActivity(activity -> {
                click(activity, "更多设置");
                click(activity, "开发者配置（高级）");
                assertNotNull(findText(activity.getWindow().getDecorView(), "高级配置"));
                assertEquals("", findEdit(activity.getWindow().getDecorView()).getText().toString());
                assertSecure(activity);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(findText(activity.getWindow().getDecorView(), "高级配置"));
                assertSecure(activity);
                click(activity, "返回");
                assertNotNull(findButton(activity.getWindow().getDecorView(), "显示与操作"));
                click(activity, "返回");
                click(activity, "Apple Music 账号");
                assertNotNull(findButton(activity.getWindow().getDecorView(), "授权 Apple Music"));
                assertNull(findButton(activity.getWindow().getDecorView(), "退出账号"));
                click(activity, "返回");
                click(activity, "返回");
                assertNotNull("Returning to the signed-out origin must restore its connect prompt",
                        findButton(activity.getWindow().getDecorView(), "连接 Apple Music"));
                assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
            });
        }
    }

    @Test public void backgroundStatusReportsActualAndroidChecksAndLaunchesRealSettings() throws Exception {
        new TokenVault(context).clear();
        AtomicReference<Intent> launched = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                launched.set(intent);
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                click(activity, "设置");
                click(activity, "播放与后台");
                String expected = "Android 电池优化："
                        + (context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName())
                        ? "已豁免" : "未豁免")
                        + "\n应用后台限制："
                        + (context.getSystemService(ActivityManager.class).isBackgroundRestricted()
                        ? "受限" : "系统未标记受限");
                assertNotNull(findText(activity.getWindow().getDecorView(), expected));
                assertNotNull(findButton(activity.getWindow().getDecorView(), "播放诊断"));
                assertSecure(activity);
            });
            captureSettings(scenario, "pocket-background.png");
            instrumentation.addMonitor(monitor);
            scenario.onActivity(activity -> {
                click(activity, "系统电源设置");
                assertNotNull("Power settings must open an actual Android settings intent", launched.get());
                assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, launched.get().getAction());
                click(activity, "应用系统设置");
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launched.get().getAction());
                assertEquals("package:" + context.getPackageName(), launched.get().getDataString());
                click(activity, "返回");
                click(activity, "更多设置");
                click(activity, "显示与操作");
                click(activity, "系统显示设置");
                assertEquals(Settings.ACTION_DISPLAY_SETTINGS, launched.get().getAction());
            });
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private void captureSettings(ActivityScenario<MainActivity> scenario, String name) throws Exception {
        // Only fixture screens are captured; production and advanced credential pages remain secure.
        scenario.onActivity(activity -> {
            assertSecure(activity);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        try {
            instrumentation.waitForIdleSync();
            Thread.sleep(400);
            Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(image);
            scenario.onActivity(activity -> {
                ViewGroup form = (ViewGroup) ((ViewGroup) field(activity, "body")).getChildAt(0);
                form = (ViewGroup) form.getChildAt(0);
                for (int i = 0; i < form.getChildCount(); i++) {
                    if (!(form.getChildAt(i) instanceof Button button)) continue;
                    Rect visible = new Rect();
                    assertTrue(button.getGlobalVisibleRect(visible));
                    assertEquals(button.getHeight(), visible.height());
                    assertEquals(button.getWidth(), visible.width());
                    assertTrue(button.getHeight() >= InkUi.dp(activity, 48));
                    assertTrue(button.getWidth() >= InkUi.dp(activity, 48));
                    ErrorRenderingTest.assertButtonPixels(activity.getWindow().getDecorView(), image,
                            List.of(button.getContentDescription().toString()));
                }
                ErrorRenderingTest.assertButtonPixels(activity.getWindow().getDecorView(), image, List.of("返回"));
            });
            try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), name))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            scenario.onActivity(activity -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
        }
    }

    private static void assertSecure(MainActivity activity) {
        assertNotEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button button && (label.contentEquals(button.getText())
                || label.contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription()))) return button;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static TextView findText(View view, String label) {
        if (view instanceof TextView text && label.contentEquals(text.getText())) return text;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findText(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static EditText findEdit(View view) {
        if (view instanceof EditText edit) return edit;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText result = findEdit(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void click(MainActivity activity, String label) {
        Button button = findButton(activity.getWindow().getDecorView(), label);
        assertNotNull(label, button);
        assertTrue(label, button.performClick());
    }
}
