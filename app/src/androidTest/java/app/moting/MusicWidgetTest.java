package app.moting;

import static org.junit.Assert.*;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.app.NotificationManager;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MusicWidgetTest {
    @Test public void providerIsResizableAndNeverSchedulesRefreshes() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName component = new ComponentName(context, MusicWidget.class);
        AppWidgetProviderInfo provider = AppWidgetManager.getInstance(context).getInstalledProviders().stream()
                .filter(info -> info.provider.equals(component)).findFirst().orElseThrow();
        assertEquals(0, provider.updatePeriodMillis);
        assertEquals(AppWidgetProviderInfo.RESIZE_HORIZONTAL | AppWidgetProviderInfo.RESIZE_VERTICAL, provider.resizeMode);
        assertEquals(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, provider.widgetCategory);
        assertEquals("Initial widget should be larger without requiring a full-height page", InkUi.dp(context, 340), provider.minHeight);
        assertEquals(InkUi.dp(context, 280), provider.minWidth);
        assertEquals(InkUi.dp(context, 220), provider.minResizeHeight);
        assertEquals(InkUi.dp(context, 220), provider.minResizeWidth);
        assertEquals(R.layout.widget_player_large, provider.initialLayout);
    }

    @Test public void actualRemoteViewsFitLargeAndCompactWidgetBounds() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new TokenVault(context).clear();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (int height : new int[]{600, 340, 220}) {
                scenario.onActivity(activity -> {
                    MusicWidget.stopped(activity);
                    FrameLayout host = new FrameLayout(activity);
                    View widget = MusicWidget.views(context, height).apply(context, host);
                    host.addView(widget, new FrameLayout.LayoutParams(
                            InkUi.dp(activity, height == 220 ? 220 : 280), InkUi.dp(activity, height)));
                    activity.setContentView(host);
                });
                AtomicBoolean focused = new AtomicBoolean();
                for (int attempt = 0; attempt < 50 && !focused.get(); attempt++) {
                    scenario.onActivity(activity -> focused.set(activity.hasWindowFocus()));
                    Thread.sleep(50);
                }
                assertTrue("Widget capture must belong to the foreground host", focused.get());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                Thread.sleep(400);
                scenario.onActivity(activity -> {
                    View root = activity.findViewById(R.id.widget_root);
                    assertEquals(InkUi.dp(activity, height), root.getHeight());
                    for (int id : new int[]{R.id.widget_title, R.id.widget_artist,
                            R.id.widget_previous, R.id.widget_play, R.id.widget_next}) {
                        View child = root.findViewById(id);
                        Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
                        ((android.view.ViewGroup) root).offsetDescendantRectToMyCoords(child, bounds);
                        assertTrue("Visible widget content needs measured bounds", bounds.width() > 0 && bounds.height() > 0);
                        assertTrue("Widget content must fit its allocated size", new Rect(0, 0, root.getWidth(), root.getHeight()).contains(bounds));
                    }
                    assertEquals("选择歌曲", ((TextView) root.findViewById(R.id.widget_title)).getText().toString());
                    assertEquals("打开 BLANK 选择歌曲", root.findViewById(R.id.widget_play).getContentDescription());
                    assertFalse(root.findViewById(R.id.widget_previous).isEnabled());
                    assertFalse(root.findViewById(R.id.widget_next).isEnabled());
                    assertEquals(height >= 400 ? View.VISIBLE : View.GONE, root.findViewById(R.id.widget_artwork).getVisibility());
                    assertEquals(height >= 300 ? View.VISIBLE : View.GONE, root.findViewById(R.id.widget_progress).getVisibility());
                });
                Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(image);
                scenario.onActivity(activity -> {
                    View play = activity.findViewById(R.id.widget_play);
                    int[] position = new int[2];
                    play.getLocationOnScreen(position);
                    assertEquals("The actual compositor must contain the widget primary control",
                            android.graphics.Color.BLACK,
                            image.getPixel(position[0] + play.getWidth() / 4, position[1] + play.getHeight() / 2));
                });
                try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), "layout-widget-" + height + ".png"))) {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
                }
            }
        }
    }

    @Test public void artworkIsBoundedAndActuallyMonochrome() {
        Bitmap color = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        color.eraseColor(Color.RED);
        Bitmap result = MusicWidget.grayscale(color);
        assertEquals(240, result.getWidth());
        assertEquals(240, result.getHeight());
        int pixel = result.getPixel(120, 120);
        assertEquals(Color.red(pixel), Color.green(pixel));
        assertEquals(Color.green(pixel), Color.blue(pixel));
        assertEquals(255, Color.alpha(pixel));
    }

    @Test public void systemWidgetHostReceivesProviderUpdatesAndResize() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        new TokenVault(context).clear();
        var automation = instrumentation.getUiAutomation();
        automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
        AtomicReference<AppWidgetHost> host = new AtomicReference<>();
        AtomicReference<AppWidgetHostView> view = new AtomicReference<>();
        AtomicInteger id = new AtomicInteger(-1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    MusicWidget.stopped(context);
                    host.set(new AppWidgetHost(context, 1050));
                    id.set(host.get().allocateAppWidgetId());
                    AppWidgetManager manager = AppWidgetManager.getInstance(context);
                    Bundle options = new Bundle();
                    options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220);
                    options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 220);
                    options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220);
                    options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220);
                    assertTrue("The system must bind the registered production provider",
                            manager.bindAppWidgetIdIfAllowed(id.get(), new ComponentName(context, MusicWidget.class), options));
                    host.get().startListening();
                    view.set(host.get().createView(context, id.get(), manager.getAppWidgetInfo(id.get())));
                    activity.setContentView(view.get());
                });
                AtomicBoolean updated = new AtomicBoolean();
                for (int i = 0; i < 50 && !updated.get(); i++) {
                    scenario.onActivity(activity -> {
                        View play = view.get().findViewById(R.id.widget_play);
                        updated.set(play != null && "打开 BLANK 选择歌曲".contentEquals(play.getContentDescription()));
                    });
                    Thread.sleep(100);
                }
                assertTrue("System host must receive live RemoteViews, not only the initial layout", updated.get());
                scenario.onActivity(activity -> {
                    assertEquals(View.GONE, view.get().findViewById(R.id.widget_artwork).getVisibility());
                    Bundle options = new Bundle();
                    options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 600);
                    options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 600);
                    AppWidgetManager.getInstance(context).updateAppWidgetOptions(id.get(), options);
                });
                updated.set(false);
                for (int i = 0; i < 50 && !updated.get(); i++) {
                    scenario.onActivity(activity -> updated.set(
                            view.get().findViewById(R.id.widget_artwork).getVisibility() == View.VISIBLE));
                    Thread.sleep(100);
                }
                assertTrue("System resize must reach the provider and update the host", updated.get());
            } finally {
                scenario.onActivity(activity -> {
                    if (host.get() != null) {
                        host.get().stopListening();
                        if (id.get() >= 0) host.get().deleteAppWidgetId(id.get());
                    }
                });
            }
        } finally {
            automation.dropShellPermissionIdentity();
        }
    }

    @Test public void staleWidgetControlShowsErrorWithoutLeavingForegroundPlayer() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new TokenVault(context).clear();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.startForegroundService(
                    new Intent(activity, PlaybackService.class).setAction(PlaybackService.TOGGLE)
                            .putExtra("widgetControl", true)));
            AtomicBoolean found = new AtomicBoolean();
            for (int i = 0; i < 40 && !found.get(); i++) {
                scenario.onActivity(activity -> {
                    View widget = MusicWidget.views(context, 600).apply(context, new FrameLayout(context));
                    found.set("请先在 BLANK 选择歌曲。".contentEquals(
                            ((TextView) widget.findViewById(R.id.widget_state)).getText()));
                });
                Thread.sleep(100);
            }
            assertTrue("A stale widget must explicitly ask for a song instead of silently doing nothing", found.get());
            boolean removed = false;
            for (int i = 0; i < 40 && !removed; i++) {
                removed = java.util.Arrays.stream(
                        context.getSystemService(NotificationManager.class).getActiveNotifications())
                        .noneMatch(notification -> notification.getId() == 7);
                if (!removed) Thread.sleep(100);
            }
            assertTrue("A failed widget command must not leave an idle foreground notification", removed);
        }
    }
}
