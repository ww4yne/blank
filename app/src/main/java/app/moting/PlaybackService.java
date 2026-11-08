package app.moting;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.graphics.Color;

import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory;
import com.apple.android.music.playback.model.MediaItemType;
import com.apple.android.music.playback.model.MediaContainerType;
import com.apple.android.music.playback.model.MediaPlayerException;
import com.apple.android.music.playback.model.ErrorConditionException;
import com.apple.android.music.playback.model.PlaybackRepeatMode;
import com.apple.android.music.playback.model.PlaybackShuffleMode;
import com.apple.android.music.playback.model.PlaybackState;
import com.apple.android.music.playback.model.PlayerMediaItem;
import com.apple.android.music.playback.model.PlayerQueueItem;
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider;
import com.apple.android.music.playback.queue.PlaybackQueueInsertionType;
import com.apple.android.sdk.authentication.TokenProvider;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

public final class PlaybackService extends Service implements MediaPlayerController.Listener {
    static final String PLAY = "io.github.ww4yne.blank.PLAY";
    static final String APPEND = "io.github.ww4yne.blank.APPEND";
    static final String TOGGLE = "io.github.ww4yne.blank.TOGGLE";
    static final String NEXT = "io.github.ww4yne.blank.NEXT";
    static final String PREVIOUS = "io.github.ww4yne.blank.PREVIOUS";
    static final String STOP = "io.github.ww4yne.blank.STOP";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION = 7;
    private final LocalBinder binder = new LocalBinder();
    private final List<Runnable> observers = new ArrayList<>();
    private MediaPlayerController player;
    private MediaSession session;
    private boolean foreground;
    private TokenVault.Tokens tokens;
    private boolean playerFailed;
    private String error = "";
    private String source = "";
    private static final long BUFFER_TIMEOUT_MS = 45000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlaybackTrace trace = new PlaybackTrace(SystemClock.elapsedRealtime());
    private Intent lastPlay;
    private boolean waitingForPlayback;
    private long watchPosition;
    private final Runnable bufferingTimeout = () -> {
        if (!waitingForPlayback || player == null) return;
        if (playing() && !buffering() && position() > watchPosition) {
            cancelPlaybackWatch();
            return;
        }
        record(PlaybackTrace.Event.TIMEOUT);
        playerFailed = true;
        fail("等待播放超过 45 秒。请点播放键重新载入，或到设置 → 播放与后台查看诊断。");
    };

    public final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW));
        // MusicKit owns audio focus and headset-disconnect handling; a second owner pauses it on resume.
        session = new MediaSession(this, "BLANK");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seek(position); }
            @Override public void onSkipToQueueItem(long id) { skip(id); }
            @Override public void onStop() { stopPlayback(); }
        });
        session.setSessionActivity(openApp());
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (intent.getBooleanExtra("widgetControl", false)) {
            if (!foreground) {
                startForeground(NOTIFICATION, notification("正在连接播放器"));
                foreground = true;
            }
            if (!hasMedia()) {
                fail("请先在 BLANK 选择歌曲。");
                return START_NOT_STICKY;
            }
        }
        if (STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }
        if (PLAY.equals(action) || APPEND.equals(action)) {
            startForeground(NOTIFICATION, notification("正在准备播放"));
            foreground = true;
            try {
                ensurePlayer();
                ArrayList<String> ids = intent.getStringArrayListExtra("ids");
                String container = intent.getStringExtra("container");
                String containerId = intent.getStringExtra("containerId");
                int startIndex = intent.getIntExtra("startIndex", 0);
                if (startIndex < 0) throw new IllegalArgumentException("播放起始位置无效。");
                CatalogPlaybackQueueItemProvider.Builder builder = new CatalogPlaybackQueueItemProvider.Builder();
                if (container != null && containerId != null && !containerId.isBlank()) {
                    builder.containers(container.equals("playlist") ? MediaContainerType.PLAYLIST
                            : MediaContainerType.ALBUM, containerId);
                } else {
                    if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("播放列表为空。");
                    if (startIndex >= ids.size()) throw new IllegalArgumentException("所选歌曲不在播放列表中。");
                    builder.items(MediaItemType.SONG, ids.toArray(new String[0]));
                }
                builder.startItemIndex(startIndex);
                error = "";
                if (APPEND.equals(action) && hasMedia()) {
                    if (!player.canAppendToPlaybackQueue()) throw new IllegalStateException("当前队列不允许追加歌曲。");
                    player.addQueueItems(builder.build(), PlaybackQueueInsertionType.INSERTION_TYPE_AT_END);
                    record(PlaybackTrace.Event.APPEND);
                } else {
                    source = intent.getStringExtra("source");
                    if (source == null) source = "Apple Music";
                    lastPlay = new Intent(intent).setAction(PLAY);
                    beginPlaybackWatch();
                    record(PlaybackTrace.Event.PREPARE);
                    player.prepare(builder.build(), true);
                }
                session.setActive(true);
            } catch (IOException | JSONException | GeneralSecurityException | IllegalStateException
                     | IllegalArgumentException | UnsatisfiedLinkError exception) {
                fail(exception instanceof UnsatisfiedLinkError
                        ? "当前系统无法加载 MusicKit ARM 音频库。请在 ARM64 实机上运行正式版 APK。"
                        : UserMessages.readable(exception));
            }
        } else if (TOGGLE.equals(action)) {
            toggle();
        } else if (NEXT.equals(action)) next();
        else if (PREVIOUS.equals(action)) previous();
        publish();
        return START_NOT_STICKY;
    }

    private void ensurePlayer() throws GeneralSecurityException, IOException, JSONException {
        TokenVault.Tokens currentTokens = new TokenVault(this).read();
        DeveloperToken.validate(currentTokens.developer(), System.currentTimeMillis() / 1000);
        if (!currentTokens.authorized()) throw new IOException("请先连接 Apple Music。");
        if (player != null && (playerFailed || tokens != null && !tokens.equals(currentTokens))) {
            record(PlaybackTrace.Event.REBUILD);
            releasePlayer();
        }
        tokens = currentTokens;
        if (player != null) return;
        System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "0");
        System.setProperty("org.bytedeco.javacpp.maxbytes", "0");
        System.loadLibrary("c++_shared");
        System.loadLibrary("appleMusicSDK");
        player = MediaPlayerControllerFactory.createLocalController(this, new Handler(Looper.getMainLooper()),
                new TokenProvider() {
                    @Override public String getDeveloperToken() { return currentTokens.developer(); }
                    @Override public String getUserToken() { return currentTokens.user(); }
                });
        playerFailed = false;
        player.addListener(this);
    }

    void observe(Runnable observer) { if (!observers.contains(observer)) observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    boolean playing() { return player != null && player.getPlaybackState() == PlaybackState.PLAYING; }
    boolean paused() { return player != null && player.getPlaybackState() == PlaybackState.PAUSED; }
    boolean buffering() { return player != null && player.isBuffering(); }
    String status() {
        if (!error.isEmpty()) return "播放遇到问题";
        if (buffering()) return "正在缓冲";
        if (playing()) return "播放中";
        if (paused()) return "已暂停";
        return hasMedia() ? "待播放" : "未播放";
    }
    String error() { return error; }
    String source() { return source; }
    long position() { return player == null ? 0 : Math.max(0, player.getCurrentPosition()); }
    long duration() { return player == null ? 0 : Math.max(0, player.getDuration()); }
    boolean canSeek() { return player != null && player.canSeek(); }
    boolean canNext() { return player != null && !playerFailed && player.canSkipToNextItem(); }
    boolean canPrevious() { return player != null && !playerFailed && player.canSkipToPreviousItem(); }
    boolean canJump() { return player != null && !playerFailed && player.canSkipToQueueItem(); }
    boolean canRetry() { return lastPlay != null && !error.isEmpty(); }
    PlayerMediaItem current() {
        return player == null || player.getCurrentItem() == null ? null : player.getCurrentItem().getItem();
    }
    boolean hasMedia() { return player != null && (player.getCurrentItem() != null || player.getPlaybackQueueItemCount() > 0); }
    List<PlayerQueueItem> queue() {
        if (player == null) return List.of();
        List<PlayerQueueItem> items = new ArrayList<>();
        PlayerQueueItem current = player.getCurrentItem();
        if (current != null) items.add(current);
        for (PlayerQueueItem next : player.getQueueItems()) {
            if (current == null || next.getPlaybackQueueId() != current.getPlaybackQueueId()) items.add(next);
        }
        return List.copyOf(items);
    }
    long currentQueueId() { return player == null || player.getCurrentItem() == null ? -1 : player.getCurrentItem().getPlaybackQueueId(); }

    void next() { if (canNext()) { error = ""; beginPlaybackWatch(); record(PlaybackTrace.Event.NEXT); player.skipToNextItem(); } }
    void previous() { if (canPrevious()) { error = ""; beginPlaybackWatch(); record(PlaybackTrace.Event.PREVIOUS); player.skipToPreviousItem(); } }
    void seek(long position) {
        if (canSeek()) {
            player.seekToPosition(Math.max(0, Math.min(position, duration())));
            publish();
        }
    }
    void skip(long id) {
        if (player != null && player.canSkipToQueueItem()) {
            error = "";
            beginPlaybackWatch();
            record(PlaybackTrace.Event.SKIP);
            player.skipToQueueItemWithId(id);
        }
    }
    void pause() {
        cancelPlaybackWatch();
        record(PlaybackTrace.Event.PAUSE);
        if (player != null) player.pause();
        publish();
    }
    void toggle() { if (playing()) pause(); else resume(); }
    void resume() {
        if (playerFailed) { fail("播放器上次加载失败，请在应用内点播放键重新载入。"); return; }
        // MusicKit's getQueueItems() excludes the current song and can be empty during valid playback.
        if (!hasMedia()) { fail("请先从资料库或搜索结果选择歌曲。"); return; }
        try {
            TokenVault.Tokens currentTokens = new TokenVault(this).read();
            DeveloperToken.validate(currentTokens.developer(), System.currentTimeMillis() / 1000);
            if (!currentTokens.authorized() || tokens != null && !tokens.equals(currentTokens)) {
                throw new IOException("账号凭据已变更，请重新选择歌曲。");
            }
        } catch (IOException | JSONException | GeneralSecurityException exception) {
            playerFailed = true;
            fail(UserMessages.readable(exception));
            return;
        }
        if (!foreground) {
            startForeground(NOTIFICATION, notification("正在恢复播放"));
            foreground = true;
        }
        error = "";
        session.setActive(true);
        beginPlaybackWatch();
        record(PlaybackTrace.Event.RESUME);
        player.play();
        publish();
    }
    private void beginPlaybackWatch() {
        handler.removeCallbacks(bufferingTimeout);
        waitingForPlayback = true;
        watchPosition = position();
        handler.postDelayed(bufferingTimeout, BUFFER_TIMEOUT_MS);
    }
    private void cancelPlaybackWatch() {
        waitingForPlayback = false;
        handler.removeCallbacks(bufferingTimeout);
    }
    String diagnostics() {
        return "BLANK " + BuildConfig.VERSION_NAME + " · MusicKit 1.1.1\nstate: 0=停止 1=播放 2=暂停\n"
                + "pos/loaded: 毫秒；error: 类型/错误码/组件/底层码\n"
                + "仅包含播放状态和数字，不含账号、Token、歌曲或链接。\n\n"
                + getSharedPreferences("playback-diagnostics", MODE_PRIVATE).getString("trace", "尚无播放记录。");
    }
    private void record(PlaybackTrace.Event event) { record(event, null); }
    private void record(PlaybackTrace.Event event, MediaPlayerException exception) {
        int cause = exception != null && exception.getCause() instanceof ErrorConditionException condition
                ? condition.getErrorCode() : 0;
        String report = trace.add(event, SystemClock.elapsedRealtime(),
                player == null ? -1 : player.getPlaybackState(), buffering(), position(),
                player == null ? -1 : player.getBufferedPosition(),
                player == null ? 0 : player.getPlaybackQueueItemCount(),
                player != null && player.getCurrentItem() != null,
                exception == null ? 0 : exception.getType(), exception == null ? 0 : exception.getErrorCode(),
                exception == null ? 0 : exception.getComponentErrorCode(), cause);
        getSharedPreferences("playback-diagnostics", MODE_PRIVATE).edit().putString("trace", report).apply();
    }
    void rebuildPlayback() {
        if (lastPlay == null) { fail("请重新选择一首歌曲，再打开播放诊断。"); return; }
        record(PlaybackTrace.Event.REBUILD);
        cancelPlaybackWatch();
        releasePlayer();
        onStartCommand(new Intent(lastPlay), 0, 0);
    }
    private void releasePlayer() {
        MediaPlayerController old = player;
        player = null;
        if (old != null) {
            old.removeListener(this);
            old.stop();
            old.release();
        }
        playerFailed = false;
    }
    String mode() {
        if (player == null) return "顺序播放";
        if (player.getShuffleMode() == PlaybackShuffleMode.SHUFFLE_MODE_SONGS) return "随机播放";
        if (player.getRepeatMode() == PlaybackRepeatMode.REPEAT_MODE_ONE) return "单曲循环";
        if (player.getRepeatMode() == PlaybackRepeatMode.REPEAT_MODE_ALL) return "列表循环";
        return "顺序播放";
    }
    void cycleMode() {
        if (player == null || !player.canSetRepeatMode() || !player.canSetShuffleMode()) {
            fail("当前播放状态不允许切换播放模式。");
            return;
        }
        String current = mode();
        player.setShuffleMode(current.equals("顺序播放")
                ? PlaybackShuffleMode.SHUFFLE_MODE_SONGS : PlaybackShuffleMode.SHUFFLE_MODE_OFF);
        player.setRepeatMode(current.equals("随机播放") ? PlaybackRepeatMode.REPEAT_MODE_ONE
                : current.equals("单曲循环") ? PlaybackRepeatMode.REPEAT_MODE_ALL : PlaybackRepeatMode.REPEAT_MODE_OFF);
        publish();
    }

    void stopPlayback() {
        cancelPlaybackWatch();
        record(PlaybackTrace.Event.STOP);
        lastPlay = null;
        releasePlayer();
        tokens = null;
        error = "";
        source = "";
        session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
        publish();
    }

    private void fail(String message) {
        cancelPlaybackWatch();
        error = message == null || message.isBlank() ? "播放失败，请重新选择歌曲或重新授权。" : message;
        Log.e("MotingPlayback", "Playback operation failed; detail shown in the application.");
        if (player != null) player.pause();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
        publish();
    }

    private void publish() {
        if (session == null) return;
        MusicWidget.publish(this);
        long actions = android.media.session.PlaybackState.ACTION_PLAY
                | android.media.session.PlaybackState.ACTION_PAUSE | android.media.session.PlaybackState.ACTION_STOP;
        if (canSeek()) actions |= android.media.session.PlaybackState.ACTION_SEEK_TO;
        if (canNext()) actions |= android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT;
        if (canPrevious()) actions |= android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (canJump()) actions |= android.media.session.PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM;
        int state = !error.isEmpty() ? android.media.session.PlaybackState.STATE_ERROR
                : buffering() ? android.media.session.PlaybackState.STATE_BUFFERING
                : playing() ? android.media.session.PlaybackState.STATE_PLAYING
                : paused() ? android.media.session.PlaybackState.STATE_PAUSED
                : android.media.session.PlaybackState.STATE_STOPPED;
        android.media.session.PlaybackState.Builder status = new android.media.session.PlaybackState.Builder()
                .setActions(actions).setState(state, position(), playing() ? 1 : 0, SystemClock.elapsedRealtime());
        if (!error.isEmpty()) status.setErrorMessage(error);
        session.setPlaybackState(status.build());
        PlayerMediaItem item = current();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item == null ? "BLANK" : item.getTitle())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item == null ? "" : item.getArtistName())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item == null ? "" : item.getAlbumTitle())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(""));
        for (Runnable observer : new ArrayList<>(observers)) observer.run();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).putExtra("openPlayer", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification.Action action(int icon, String title, String command, int request) {
        PendingIntent pending = PendingIntent.getService(this, request,
                new Intent(this, PlaybackService.class).setAction(command),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(icon, title, pending).build();
    }
    private Notification notification(String preparing) {
        PlayerMediaItem item = current();
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setColor(Color.BLACK).setColorized(false)
                .setContentTitle(item == null ? "BLANK" : item.getTitle())
                .setContentText(!preparing.isEmpty() ? preparing : item == null ? "尚未选择歌曲" : item.getArtistName())
                .setContentIntent(openApp()).setOnlyAlertOnce(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(playing() || buffering())
                .addAction(action(R.drawable.ic_previous, "上一首", PREVIOUS, 1))
                .addAction(action(playing() ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing() ? "暂停" : "播放", TOGGLE, 2))
                .addAction(action(R.drawable.ic_next, "下一首", NEXT, 3))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)).build();
    }

    @Override public void onDestroy() {
        cancelPlaybackWatch();
        MusicWidget.stopped(this);
        if (player != null) { player.removeListener(this); player.release(); }
        session.release();
        observers.clear();
        super.onDestroy();
    }

    @Override public void onPlayerStateRestored(MediaPlayerController controller) { if (controller == player) publish(); }
    @Override public void onPlaybackStateChanged(MediaPlayerController controller, int previous, int current) {
        if (controller != player) return;
        record(PlaybackTrace.Event.STATE);
        // prepare() can report STOPPED before the first queue item is resolved.
        if (current == PlaybackState.PAUSED) cancelPlaybackWatch();
        if (current == PlaybackState.PLAYING && buffering() && !waitingForPlayback && error.isEmpty()) beginPlaybackWatch();
        if (current == PlaybackState.PLAYING && playing() && !buffering() && !playerFailed) error = "";
        publish();
    }
    @Override public void onPlaybackStateUpdated(MediaPlayerController controller) {
        // Position is sampled by the visible UI; SDK ticks must not repaint an e-ink screen.
        if (controller == player && waitingForPlayback && playing() && !buffering() && position() > watchPosition) {
            cancelPlaybackWatch();
        }
    }
    @Override public void onBufferingStateChanged(MediaPlayerController controller, boolean buffering) {
        if (controller != player) return;
        record(PlaybackTrace.Event.BUFFER);
        if (buffering && !waitingForPlayback && playing() && error.isEmpty()) beginPlaybackWatch();
        publish();
    }
    @Override public void onCurrentItemChanged(MediaPlayerController controller, PlayerQueueItem previous, PlayerQueueItem current) {
        if (controller != player) return;
        if (waitingForPlayback) watchPosition = position();
        record(PlaybackTrace.Event.ITEM);
        publish();
    }
    @Override public void onItemEnded(MediaPlayerController controller, PlayerQueueItem item, long position) { if (controller == player) publish(); }
    @Override public void onMetadataUpdated(MediaPlayerController controller, PlayerQueueItem current) { if (controller == player) publish(); }
    @Override public void onPlaybackQueueChanged(MediaPlayerController controller, List<PlayerQueueItem> items) {
        if (controller != player) return;
        record(PlaybackTrace.Event.QUEUE);
        publish();
    }
    @Override public void onPlaybackQueueItemsAdded(MediaPlayerController controller, int insertion, int container, int type) { if (controller == player) publish(); }
    @Override public void onPlaybackError(MediaPlayerController controller, MediaPlayerException exception) {
        if (controller != player) return;
        playerFailed = true;
        record(PlaybackTrace.Event.ERROR, exception);
        Log.e("MotingPlayback", "SDK error type=" + exception.getType() + " code=" + exception.getErrorCode());
        fail(switch (exception.getType()) {
            case MediaPlayerException.TYPE_DRM -> "MusicKit 音频解密失败。请查看设置 → 播放与后台中的诊断。";
            case MediaPlayerException.TYPE_PLAYBACK_LEASE -> "MusicKit 播放许可未通过。请停止其他设备播放后重试，或重新授权。";
            case MediaPlayerException.TYPE_IO -> "MusicKit 加载音频失败。请查看设置 → 播放与后台中的诊断。";
            default -> "MusicKit 无法播放。请查看设置 → 播放与后台中的诊断。";
        });
    }
    @Override public void onPlaybackRepeatModeChanged(MediaPlayerController controller, int mode) { if (controller == player) publish(); }
    @Override public void onPlaybackShuffleModeChanged(MediaPlayerController controller, int mode) { if (controller == player) publish(); }
}
