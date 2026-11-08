package app.moting;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Objects;

public final class MusicWidget extends AppWidgetProvider {
    private record State(String title, String artist, String album, String artwork, String error,
                         boolean hasMedia, boolean playing, boolean paused, boolean buffering, boolean previous,
                         boolean next, long position, long duration) {}
    private static final State EMPTY = new State("选择歌曲", "打开 BLANK 开始聆听", "", "", "",
            false, false, false, false, false, false, 0, 0);
    private static volatile State state = EMPTY;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService images = Executors.newSingleThreadExecutor();
    private static String requestedArtwork = "", artworkError = "";
    private static Bitmap artwork;
    private static int artworkGeneration;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        updateOne(context, manager, id, options);
        loadArtwork(context);
    }

    @Override public void onDisabled(Context context) {
        artworkGeneration++;
        requestedArtwork = "";
        artworkError = "";
        artwork = null;
    }

    static void publish(PlaybackService service) {
        var item = service.current();
        State updated = item == null ? new State(EMPTY.title(), EMPTY.artist(), "", "", service.error(),
                service.hasMedia(), false, false, service.buffering(), false, false, 0, 0)
                : new State(item.getTitle(), item.getArtistName(), item.getAlbumTitle(),
                item.getArtworkUrl(240, 240), service.error(), service.hasMedia(), service.playing(), service.paused(),
                service.buffering(), service.canPrevious(), service.canNext(), service.position(), service.duration());
        if (updated.equals(state)) return;
        if (!Objects.equals(updated.artwork(), state.artwork())) {
            artworkGeneration++;
            requestedArtwork = "";
            artworkError = "";
            artwork = null;
        }
        state = updated;
        updateAll(service);
    }

    static void stopped(Context context) {
        state = new State(EMPTY.title(), EMPTY.artist(), "", "", state.error(),
                false, false, false, false, false, false, 0, 0);
        artworkGeneration++;
        requestedArtwork = "";
        artworkError = "";
        artwork = null;
        updateAll(context);
    }

    private static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, MusicWidget.class))) {
            updateOne(context, manager, id, manager.getAppWidgetOptions(id));
        }
        loadArtwork(context);
    }

    private static void updateOne(Context context, AppWidgetManager manager, int id, Bundle options) {
        int minimum = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220);
        int maximum = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minimum);
        manager.updateAppWidget(id, new RemoteViews(views(context, minimum), views(context, maximum)));
    }

    static RemoteViews views(Context context, int height) {
        State current = state;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_player_large);
        views.setTextViewText(R.id.widget_title, current.title());
        views.setTextViewText(R.id.widget_artist, current.artist());
        views.setTextViewText(R.id.widget_album, current.album());
        String status = !current.error().isEmpty() ? current.error() : current.buffering() ? "缓冲中"
                : current.playing() ? "播放中" : current.paused() ? "已暂停" : current.hasMedia() ? "待播放" : "未播放";
        if (current.error().isEmpty() && !artworkError.isEmpty()) status += " · 封面不可用";
        views.setTextViewText(R.id.widget_state, status);
        views.setContentDescription(R.id.widget_state, status);
        views.setTextViewText(R.id.widget_elapsed, MainActivity.formatTime(current.position()));
        views.setTextViewText(R.id.widget_duration, MainActivity.formatTime(current.duration()));
        int progress = current.duration() <= 0 ? 0
                : (int) Math.min(1000, current.position() * 1000 / current.duration());
        views.setProgressBar(R.id.widget_progress, 1000, progress, false);
        views.setImageViewResource(R.id.widget_play, current.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
        views.setContentDescription(R.id.widget_play, current.hasMedia()
                ? current.playing() ? "暂停" : "播放" : "打开 BLANK 选择歌曲");
        views.setImageViewResource(R.id.widget_artwork, R.drawable.ic_album_placeholder);
        if (artwork != null && current.artwork() != null && current.artwork().equals(requestedArtwork)) {
            views.setImageViewBitmap(R.id.widget_artwork, artwork);
        }
        views.setViewVisibility(R.id.widget_artwork, height >= 400 ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_spacer, height >= 400 ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.widget_album, height >= 400 && current.album() != null
                && !current.album().isEmpty() ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_header, height >= 300 ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_progress, height >= 300 ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_times, height >= 300 ? View.VISIBLE : View.GONE);
        views.setInt(R.id.widget_title, "setMaxLines", height >= 400 ? 2 : 1);
        PendingIntent open = PendingIntent.getActivity(context, 40, new Intent(context, MainActivity.class)
                .putExtra("openPlayer", current.hasMedia())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        views.setOnClickPendingIntent(R.id.widget_artwork, open);
        views.setOnClickPendingIntent(R.id.widget_title, open);
        views.setOnClickPendingIntent(R.id.widget_play, current.hasMedia() ? command(context, PlaybackService.TOGGLE, 41) : open);
        views.setOnClickPendingIntent(R.id.widget_previous, command(context, PlaybackService.PREVIOUS, 42));
        views.setOnClickPendingIntent(R.id.widget_next, command(context, PlaybackService.NEXT, 43));
        views.setBoolean(R.id.widget_previous, "setEnabled", current.previous());
        views.setBoolean(R.id.widget_next, "setEnabled", current.next());
        views.setInt(R.id.widget_previous, "setImageAlpha", current.previous() ? 255 : 90);
        views.setInt(R.id.widget_next, "setImageAlpha", current.next() ? 255 : 90);
        return views;
    }

    private static PendingIntent command(Context context, String action, int code) {
        return PendingIntent.getForegroundService(context, code, new Intent(context, PlaybackService.class)
                        .setAction(action).putExtra("widgetControl", true),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void loadArtwork(Context context) {
        String url = state.artwork();
        if (url == null || url.isEmpty() || url.equals(requestedArtwork)) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        boolean visibleCover = false;
        for (int id : manager.getAppWidgetIds(new ComponentName(context, MusicWidget.class))) {
            if (manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220) >= 400) {
                visibleCover = true;
                break;
            }
        }
        if (!visibleCover) return;
        requestedArtwork = url;
        artworkError = "";
        artwork = null;
        int generation = ++artworkGeneration;
        Context app = context.getApplicationContext();
        images.execute(() -> {
            try {
                Bitmap source = Artwork.load(url);
                Bitmap grayscale = grayscale(source);
                main.post(() -> {
                    if (generation != artworkGeneration || !url.equals(state.artwork())) return;
                    artwork = grayscale;
                    updateAll(app);
                });
            } catch (IOException exception) {
                main.post(() -> {
                    if (generation != artworkGeneration || !url.equals(state.artwork())) return;
                    artworkError = exception.getMessage();
                    if (artworkError == null || artworkError.isEmpty()) artworkError = "封面不可用";
                    updateAll(app);
                });
            }

        });
    }

    static Bitmap grayscale(Bitmap source) {
        Bitmap result = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        new Canvas(result).drawBitmap(source, null, new Rect(0, 0, 240, 240), paint);
        return result;
    }
}
