package app.moting;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.apple.android.music.playback.model.PlayerMediaItem;
import com.apple.android.music.playback.model.PlayerQueueItem;
import com.apple.android.sdk.authentication.AuthenticationFactory;
import com.apple.android.sdk.authentication.AuthenticationManager;
import com.apple.android.sdk.authentication.TokenResult;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final int AUTH_REQUEST = 30;
    private static final int APPLE_MUSIC_MIN_VERSION = 680; // Required by the bundled MusicKit 1.1.2 SDK.
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<BrowseState> history = new ArrayDeque<>();
    private final List<MusicData.Item> items = new ArrayList<>();
    private final Map<String, MusicData.Group> recommendationGroups = new LinkedHashMap<>();
    private TokenVault vault;
    private AppleMusicApi api;
    private AuthenticationManager authentication;
    private boolean authorizationPending;
    private PlaybackService playback;
    private boolean bound, visible, loading;
    private int requestGeneration, page, pageAnchor, pageSize = 5, queuePage, queuePageSize = 5;
    private String screen = "推荐", browseTitle = "推荐", libraryKind = "playlists", next = "";
    private String searchKind = "songs", searchTerm = "";
    private String accountError = "", metadataKey = "", artworkKey = "";
    private LinearLayout root, body, rows, pager, mini;
    private TextView message, miniTitle, miniArtist;
    private InkUi.IconButton miniPlay, miniNext, miniPrevious, miniMode;
    private TextView playerTitle, playerArtist, playerAlbum, playerSource, elapsed, total, playerState;
    private InkUi.IconButton playerPlay, playerNext, playerPrevious, playerMode;
    private SeekBar seek;
    private InkUi.Cover cover;
    private EditText search;
    private Bitmap artwork;
    private String failedArtworkKey = "";
    private MusicData.Item browsingContainer;
    private SettingsPage settingsPage = SettingsPage.ROOT;
    private BrowseState settingsOrigin;
    private String settingsReturn = "播放";
    private TextView backgroundStatus;
    private Button chooseMusic;
    private Button songActions;
    private boolean songActionPending;
    private String songActionMessage = "";

    private enum SettingsPage { ROOT, ACCOUNT, BACKGROUND, MORE, DISPLAY, ABOUT, ADVANCED }

    private record BrowseState(String screen, String title, List<MusicData.Item> items,
                               String next, int anchor, String kind, MusicData.Item container) {}
    private interface Query { MusicData.Page run() throws IOException, JSONException, GeneralSecurityException; }

    private final Runnable observer = this::updatePlayback;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (getSystemService(PowerManager.class).isInteractive()
                    && playback != null && playback.playing() && screen.equals("播放")) updateProgress();
            handler.postDelayed(this, 15000);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            if (visible) playback.observe(observer);
            updatePlayback();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            if (visible) { setMessage("播放服务已断开，请重新打开应用。"); updatePlayback(); }
        }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        vault = new TokenVault(this);
        api = new AppleMusicApi(vault);
        if (savedState == null && credentials().authorized()) screen = "播放";
        if (savedState != null) {
            authorizationPending = savedState.getBoolean("authorizationPending", false);
            screen = savedState.getString("screen", "推荐");
            if (screen.equals("为你")) screen = "推荐";
            libraryKind = savedState.getString("kind", "playlists");
            searchKind = savedState.getString("searchKind", "songs");
            searchTerm = savedState.getString("searchTerm", "");
            if (!List.of("推荐", "资料库", "搜索", "播放", "队列", "设置").contains(screen)) screen = "推荐";
            settingsReturn = savedState.getString("settingsReturn", "播放");
            for (SettingsPage value : SettingsPage.values()) {
                if (value.name().equals(savedState.getString("settingsPage", ""))) settingsPage = value;
            }
        }
        if (getIntent().getBooleanExtra("openPlayer", false)) {
            screen = "播放";
            getIntent().removeExtra("openPlayer");
        }
        if (screen.equals("设置")) settingsLayout(settingsPage);
        else if (screen.equals("播放")) showPlayer();
        else if (screen.equals("队列")) showQueue();
        else navigate(screen);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("openPlayer", false)) {
            intent.removeExtra("openPlayer");
            showPlayer();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", screen);
        state.putString("kind", libraryKind);
        state.putString("searchKind", searchKind);
        state.putString("searchTerm", search == null ? searchTerm : search.getText().toString());
        state.putBoolean("authorizationPending", authorizationPending);
        state.putString("settingsPage", settingsPage.name());
        state.putString("settingsReturn", settingsReturn);
    }
    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (authorizationPending) {
            authorizationPending = false;
            setMessage("未收到授权结果，请重试。");
        }
        if (playback != null) playback.observe(observer);
        updatePlayback();
        if (screen.equals("设置") && settingsPage == SettingsPage.BACKGROUND) updateBackgroundStatus();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 15000);
    }
    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(ticker);
        if (playback != null) playback.unobserve(observer);
        super.onPause();
    }
    @Override protected void onStop() {
        if (playback != null) playback.unobserve(observer);
        if (bound) unbindService(connection);
        bound = false;
        playback = null;
        super.onStop();
    }
    @Override protected void onDestroy() {
        requestGeneration++;
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TokenVault.Tokens credentials() {
        try {
            accountError = "";
            return vault.read();
        } catch (GeneralSecurityException | IOException | JSONException e) {
            accountError = "无法读取加密凭据，请在设置中退出账号后重新连接。";
            return new TokenVault.Tokens("", "");
        }
    }

    private void base(String title) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        requestGeneration++;
        loading = false;
        playerTitle = null;
        miniTitle = null;
        mini = null;
        miniPlay = miniNext = miniPrevious = miniMode = null;
        chooseMusic = null;
        songActions = null;
        backgroundStatus = null;
        rows = null;
        seek = null;
        cover = null;
        root = InkUi.column(this);
        root.setPadding(dp(18), dp(8), dp(18), 0);
        LinearLayout header = InkUi.row(this);
        TextView heading = text(title, 24, true);
        InkUi.singleLine(heading);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(58), 1));
        if (!screen.equals("设置")) {
            if (screen.equals("播放")) {
                songActions = new InkUi.IconButton(this, R.drawable.ic_more, "曲目操作", false, this::showSongActions);
                header.addView(songActions, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            header.addView(new InkUi.IconButton(this, R.drawable.ic_search, "搜索", false, () -> navigate("搜索")),
                    new LinearLayout.LayoutParams(dp(48), dp(48)));
            header.addView(new InkUi.IconButton(this, R.drawable.ic_settings, "设置", false, this::showSettings),
                    new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else header.addView(button("返回", false, this::settingsBack), new LinearLayout.LayoutParams(dp(60), dp(48)));
        root.addView(header);
        message = text("", 12, false);
        message.setPadding(0, dp(10), 0, dp(10));
        message.setVisibility(View.GONE);
        root.addView(message);
        body = InkUi.column(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void setMessage(String value) {
        if (message == null) return;
        if (!message.getText().toString().equals(value)) {
            message.setText(value);
            message.setOnClickListener(null);
        }
        message.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void addNavigation() {
        InkUi.line(root);
        LinearLayout nav = InkUi.row(this);
        for (String target : List.of("播放", "推荐", "资料库")) {
            Button button = button(target, screen.equals(target), () -> {
                if (target.equals("播放")) showPlayer(); else navigate(target);
            });
            if (target.equals("播放")) button.setContentDescription("播放页面");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1);
            params.setMargins(dp(3), dp(4), dp(3), dp(4));
            nav.addView(button, params);
        }
        root.addView(nav);
    }
    private void addMini() {
        mini = InkUi.column(this);
        InkUi.line(mini);
        LinearLayout copy = InkUi.row(this);
        miniTitle = text("尚未播放", 14, true);
        InkUi.singleLine(miniTitle);
        miniArtist = text("未播放", 11, false);
        InkUi.singleLine(miniArtist);
        miniArtist.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        copy.addView(miniTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        copy.addView(miniArtist, new LinearLayout.LayoutParams(dp(76), dp(48)));
        copy.setPadding(0, 0, 0, 0);
        copy.setOnClickListener(view -> showPlayer());
        copy.setContentDescription("打开正在播放");
        copy.setMinimumHeight(dp(48));
        copy.setFocusable(true);
        mini.addView(copy);
        mini.addView(mediaControls(true));
        root.addView(mini);
        updatePlayback();
    }

    private LinearLayout mediaControls(boolean compact) {
        LinearLayout controls = InkUi.row(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(8), 0, dp(8));
        InkUi.IconButton mode = new InkUi.IconButton(this, R.drawable.ic_order, "顺序播放", false,
                () -> { if (playback != null) playback.cycleMode(); });
        InkUi.IconButton previous = new InkUi.IconButton(this, R.drawable.ic_previous, "上一首", false,
                () -> { if (playback != null) playback.previous(); });
        InkUi.IconButton play = new InkUi.IconButton(this, R.drawable.ic_play, "播放", true, this::toggle);
        InkUi.IconButton nextButton = new InkUi.IconButton(this, R.drawable.ic_next, "下一首", false,
                () -> { if (playback != null) playback.next(); });
        controls.addView(mode, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams previousParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        previousParams.leftMargin = dp(4);
        controls.addView(previous, previousParams);
        int size = dp(64);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(size, size);
        playParams.setMargins(dp(4), 0, dp(4), 0);
        controls.addView(play, playParams);
        controls.addView(nextButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams queueParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        queueParams.leftMargin = dp(4);
        controls.addView(new InkUi.IconButton(this, R.drawable.ic_queue, "播放队列", false, this::showQueue), queueParams);
        if (compact) {
            miniMode = mode; miniPrevious = previous; miniPlay = play; miniNext = nextButton;
        } else {
            playerMode = mode; playerPrevious = previous; playerPlay = play; playerNext = nextButton;
        }
        return controls;
    }

    private void navigate(String target) {
        history.clear();
        browsingContainer = null;
        items.clear();
        next = "";
        page = pageAnchor = 0;
        screen = target;
        browseTitle = target;
        browseLayout();
        if (!credentials().authorized()) { connectionPrompt(); return; }
        if (target.equals("推荐")) { recommendationGroups.clear(); query(api::recommendations, false); }
        else if (target.equals("资料库")) query(() -> api.library(libraryKind), false);
        else setMessage("");
    }

    private void connectionPrompt() {
        rows.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout welcome = InkUi.column(this);
        welcome.setPadding(0, dp(24), 0, 0);
        if (!accountError.isEmpty()) {
            TextView issue = text(accountError, 14, false);
            issue.setPadding(0, 0, 0, dp(16));
            welcome.addView(issue);
        }
        welcome.addView(button("连接 Apple Music", true, () -> {
            if (credentials().developer().isEmpty()) showSettings(); else authorize();
        }));
        scroll.addView(welcome);
        rows.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        if (pager != null) pager.setVisibility(View.GONE);
    }

    private void browseLayout() {
        base(browseTitle);
        if (screen.equals("资料库") && history.isEmpty()) {
            LinearLayout tabs = InkUi.row(this);
            String[] kinds = {"playlists", "albums", "songs"};
            String[] labels = {"歌单", "专辑", "歌曲"};
            for (int i = 0; i < kinds.length; i++) {
                String kind = kinds[i];
                Button tab = button(labels[i], kind.equals(libraryKind), () -> { libraryKind = kind; navigate("资料库"); });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
                params.setMargins(dp(3), dp(12), dp(3), dp(10));
                tabs.addView(tab, params);
            }
            body.addView(tabs);
        }
        if (screen.equals("搜索") && history.isEmpty()) {
            LinearLayout form = InkUi.row(this);
            search = new EditText(this);
            search.setSingleLine(true);
            search.setTextSize(16);
            search.setTextColor(Color.BLACK);
            search.setHintTextColor(Color.DKGRAY);
            search.setHint("歌曲、歌手或专辑");
            search.setText(searchTerm);
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setBackground(InkUi.underline(this, true));
            search.setPadding(dp(8), dp(8), dp(8), dp(8));
            form.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button submit = button("搜索", true, this::submitSearch);
            submit.setContentDescription("执行搜索");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(48));
            params.leftMargin = dp(6);
            form.addView(submit, params);
            LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(-1, dp(48));
            formParams.topMargin = dp(14);
            body.addView(form, formParams);
            search.setOnEditorActionListener((view, action, event) -> { submitSearch(); return true; });
            LinearLayout categories = InkUi.row(this);
            String[] kinds = {"songs", "albums", "playlists"};
            String[] labels = {"歌曲", "专辑", "歌单"};
            for (int i = 0; i < kinds.length; i++) {
                String kind = kinds[i];
                Button category = button(labels[i], kind.equals(searchKind), () -> {
                    searchTerm = search.getText().toString().trim();
                    searchKind = kind;
                    items.clear(); page = pageAnchor = 0; next = "";
                    browseLayout();
                    if (!searchTerm.isEmpty() && credentials().authorized()) submitSearch();
                });
                LinearLayout.LayoutParams categoryParams = new LinearLayout.LayoutParams(0, dp(48), 1);
                categoryParams.setMargins(dp(3), dp(12), dp(3), dp(10));
                categories.addView(category, categoryParams);
            }
            body.addView(categories);
        }
        if (!history.isEmpty()) {
            LinearLayout summary = InkUi.row(this);
            summary.addView(button("返回", false, this::goBack), new LinearLayout.LayoutParams(dp(64), dp(48)));
            summary.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            if (browsingContainer != null && !browsingContainer.group()) {
                summary.addView(new InkUi.IconButton(this, R.drawable.ic_play, "播放全部", false, this::playAll),
                        new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, dp(48));
            summaryParams.setMargins(0, dp(8), 0, dp(8));
            body.addView(summary, summaryParams);
        }
        rows = InkUi.column(this);
        rows.setPadding(0, dp(8), 0, dp(8));
        body.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        // Rebuilding children inside layout can leave the new rows unmeasured.
        rows.addOnLayoutChangeListener((view, left, top, right, bottom, ol, ot, or, ob) -> view.post(() -> {
            if (isDestroyed() || rows != view || loading) return;
            int available = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
            int calculated = Math.max(1, Math.min(12, available / (browseRowHeight() + dp(1))));
            if (calculated != pageSize) {
                pageSize = calculated;
                if (credentials().authorized()) renderRows();
            }
        }));
        pager = InkUi.row(this);
        body.addView(pager, new LinearLayout.LayoutParams(-1, dp(62)));
        addMini();
        addNavigation();
        renderRows();
    }

    private void submitSearch() {
        if (!credentials().authorized()) { showSettings(); return; }
        String term = search.getText().toString().trim();
        if (term.isEmpty()) { setMessage("请输入搜索关键词。"); return; }
        searchTerm = term;
        String kind = searchKind;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        query(() -> api.search(term, kind), false);
    }
    private void query(Query query, boolean append) {
        final int generation = ++requestGeneration;
        loading = true;
        message.setOnClickListener(null);
        setMessage("正在加载…");
        worker.execute(() -> {
            try {
                MusicData.Page result = query.run();
                handler.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    loading = false;
                    for (MusicData.Group group : result.groups()) recommendationGroups.put(group.id(), group);
                    if (!append) { items.clear(); pageAnchor = 0; }
                    int oldCount = items.size();
                    for (MusicData.Item item : result.items()) {
                        if (browsingContainer != null || !items.contains(item)) items.add(item);
                    }
                    if (append) {
                        pageAnchor = oldCount;
                    }
                    next = result.next();
                    setMessage(result.items().isEmpty() && items.isEmpty()
                            ? "暂无内容。" : "");
                    renderRows();
                });
            } catch (IOException | JSONException | GeneralSecurityException exception) {
                handler.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    loading = false;
                    setMessage("加载失败：" + readable(exception) + "\n点按此提示重试。");
                    message.setOnClickListener(view -> { if (!loading) query(query, append); });
                    renderRows();
                });
            }
        });
    }

    private void renderRows() {
        if (rows == null || screen.equals("队列") || screen.equals("设置") || screen.equals("播放")) return;
        if (!credentials().authorized()) { connectionPrompt(); renderPager(); return; }
        rows.removeAllViews();
        if (items.isEmpty() && !loading && message.getVisibility() == View.GONE
                && !(screen.equals("搜索") && history.isEmpty() && searchTerm.isEmpty())) {
            TextView empty = text("暂无内容", 14, false);
            empty.setPadding(dp(6), dp(16), 0, 0);
            rows.addView(empty);
        }
        pageAnchor = Math.max(0, Math.min(pageAnchor, Math.max(0, items.size() - 1)));
        page = pageAnchor / pageSize;
        int from = page * pageSize, to = Math.min(items.size(), from + pageSize);
        for (int index = from; index < to; index++) {
            int itemIndex = index;
            MusicData.Item item = items.get(index);
            LinearLayout row = InkUi.row(this);
            row.setBackgroundColor(Color.WHITE);
            LinearLayout copy = InkUi.column(this);
            TextView name = text(item.title(), 16, true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView subtitle = text(item.subtitle(), 12, false);
            subtitle.setPadding(0, dp(4), 0, 0);
            InkUi.singleLine(subtitle);
            copy.addView(name);
            if (!item.subtitle().isEmpty()) copy.addView(subtitle);
            row.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));
            copy.setGravity(Gravity.CENTER_VERTICAL);
            copy.setPadding(dp(6), dp(6), dp(8), dp(6));
            TextView action = text(item.song() ? "播放" : item.group() ? "进入" : "查看", 12, false);
            row.addView(action, new LinearLayout.LayoutParams(dp(38), -1));
            row.setOnClickListener(view -> open(item, itemIndex));
            row.setOnLongClickListener(view -> {
                if (!item.song()) return false;
                new AlertDialog.Builder(this).setTitle(item.title())
                        .setItems(new String[]{"立即播放", "加入播放队列"}, (dialog, which) -> {
                            if (which == 0) startSongInList(item, itemIndex);
                            else startSongs(List.of(item), true, 0, "");
                        })
                        .setNegativeButton("取消", null).show();
                return true;
            });
            row.setFocusable(true);
            row.setContentDescription(item.title() + "，" + item.subtitle() + (item.song() ? "，点按播放，长按加入队列" : "，查看曲目"));
            rows.addView(row, new LinearLayout.LayoutParams(-1, browseRowHeight()));
            InkUi.line(rows, Color.LTGRAY);
        }
        renderPager();
    }
    private int browseRowHeight() {
        return dp(64 * Math.max(1f, getResources().getConfiguration().fontScale));
    }

    private void renderPager() {
        pager.removeAllViews();
        if (!credentials().authorized() || (items.size() <= pageSize && next.isEmpty())) {
            pager.setVisibility(View.GONE);
            return;
        }
        pager.setVisibility(View.VISIBLE);
        Button previous = button("上一页", false, () -> {
            if (page > 0 && !loading) { pageAnchor = (page - 1) * pageSize; renderRows(); }
        });
        InkUi.enabled(previous, page > 0 && !loading);
        pager.addView(previous, new LinearLayout.LayoutParams(dp(84), dp(48)));
        TextView index = text(String.format(Locale.ROOT, "%02d / %02d%s", page + 1,
                Math.max(1, (items.size() + pageSize - 1) / pageSize), next.isEmpty() ? "" : "+"), 12, false);
        index.setGravity(Gravity.CENTER);
        pager.addView(index, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button following = button("下一页", false, () -> {
            if (loading) return;
            if ((page + 1) * pageSize < items.size()) { pageAnchor = (page + 1) * pageSize; renderRows(); }
            else if (!next.isEmpty()) {
                String route = next;
                boolean tracks = browsingContainer != null && !browsingContainer.group();
                query(() -> tracks ? api.trackPage(route) : api.page(route), true);
            }
        });
        InkUi.enabled(following, !loading && ((page + 1) * pageSize < items.size() || !next.isEmpty()));
        pager.addView(following, new LinearLayout.LayoutParams(dp(84), dp(48)));
    }

    private void open(MusicData.Item item, int index) {
        if (loading) return;
        if (item.song()) { startSongInList(item, index); return; }
        MusicData.Group group = item.group() ? recommendationGroups.get(item.id()) : null;
        if (item.group() && group == null) { setMessage("此分类尚未加载，请返回推荐重试。"); return; }
        history.push(new BrowseState(screen, browseTitle, List.copyOf(items), next, pageAnchor, libraryKind, browsingContainer));
        browsingContainer = item;
        browseTitle = item.title();
        items.clear();
        next = "";
        page = pageAnchor = 0;
        browseLayout();
        if (group != null) {
            items.addAll(group.items());
            next = group.next();
            if (items.isEmpty() && !next.isEmpty()) {
                String route = next;
                query(() -> api.page(route), false);
            } else renderRows();
        } else query(() -> api.tracks(item), false);
    }
    private void goBack() {
        if (history.isEmpty()) { navigate("推荐"); return; }
        BrowseState previous = history.pop();
        screen = previous.screen(); browseTitle = previous.title(); next = previous.next();
        items.clear(); items.addAll(previous.items()); pageAnchor = previous.anchor(); libraryKind = previous.kind();
        browsingContainer = previous.container();
        browseLayout();
    }
    @Override public void onBackPressed() {
        if (screen.equals("设置")) settingsBack();
        else if (screen.equals("队列")) showPlayer();
        else if (screen.equals("播放")) super.onBackPressed();
        else if (!history.isEmpty()) goBack();
        else super.onBackPressed();
    }

    private void playAll() {
        if (loading || browsingContainer == null || browsingContainer.group()) return;
        MusicData.Item container = browsingContainer;
        if (!container.library() && !container.catalogId().isEmpty()) {
            startContainer(container, 0);
            return;
        }
        startSongs(MusicData.songs(List.copyOf(items)), false, 0, next);
    }

    private void startContainer(MusicData.Item container, int index) {
        dispatchPlayback(new Intent(this, PlaybackService.class).setAction(PlaybackService.PLAY)
                .putExtra("container", container.playlist() ? "playlist" : "album")
                .putExtra("containerId", container.catalogId()).putExtra("startIndex", index)
                .putExtra("source", container.title()), false);
    }

    private void startSongInList(MusicData.Item selected, int itemIndex) {
        if (loading) return;
        if (itemIndex < 0 || itemIndex >= items.size() || !selected.equals(items.get(itemIndex))) {
            setMessage("此歌曲已不在当前列表，请重新打开列表。");
            return;
        }
        List<MusicData.Item> songs = MusicData.songs(List.copyOf(items));
        int index = MusicData.songs(items.subList(0, itemIndex)).size();
        if (browsingContainer != null && !browsingContainer.group()
                && !browsingContainer.library() && !browsingContainer.catalogId().isEmpty()) {
            startContainer(browsingContainer, index);
        } else startSongs(songs, false, index, next);
    }

    private void dispatchPlayback(Intent command, boolean append) {
        if (!visible) { setMessage("应用已进入后台，请返回后重新点按播放。"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 31);
        }
        startForegroundService(command);
        if (append) setMessage("已发送加入队列请求。");
        else showPlayer();
    }

    private void startSongs(List<MusicData.Item> songs, boolean append, int startIndex, String continuation) {
        if (loading) return;
        if (!credentials().authorized()) { showSettings(); return; }
        final int generation = ++requestGeneration;
        final String source = browseTitle;
        final boolean tracks = browsingContainer != null && !browsingContainer.group();
        loading = true;
        setMessage(append ? "正在解析歌曲…" : "正在准备播放列表…");
        worker.execute(() -> {
            try {
                ArrayList<String> ids = new ArrayList<>();
                Set<String> visited = new HashSet<>();
                MusicData.Page result = new MusicData.Page(songs, continuation);
                while (true) {
                    for (MusicData.Item song : MusicData.songs(result.items())) ids.add(api.songId(song));
                    if (result.next().isEmpty()) break;
                    if (!visited.add(result.next())) throw new IOException("Apple 返回了重复的分页地址，已终止加载。");
                    if (Thread.currentThread().isInterrupted()) throw new IOException("加载已取消。");
                    result = tracks ? api.trackPage(result.next()) : api.page(result.next());
                }
                if (ids.isEmpty() || startIndex < 0 || startIndex >= ids.size()) throw new IOException("播放列表中没有所选歌曲。");
                handler.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    loading = false;
                    Intent command = new Intent(this, PlaybackService.class)
                            .setAction(append ? PlaybackService.APPEND : PlaybackService.PLAY)
                            .putStringArrayListExtra("ids", ids).putExtra("source", source)
                            .putExtra("startIndex", startIndex);
                    dispatchPlayback(command, append);
                });
            } catch (IOException | JSONException | GeneralSecurityException exception) {
                handler.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    loading = false;
                    setMessage("无法播放：" + readable(exception));
                });
            }
        });
    }

    private void toggle() {
        if (playback == null) { setMessage("播放服务尚未连接，请稍后重试。"); return; }
        if (!playback.error().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("重新载入播放内容？")
                    .setMessage("播放器上次加载失败。重新载入上次选择的歌曲或列表，进度和之后追加的队列不会保留。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重新载入", (dialog, which) -> {
                        if (playback != null) playback.rebuildPlayback();
                    }).show();
            return;
        }
        playback.toggle();
    }

    private void showSongActions() {
        PlayerMediaItem item = playback == null ? null : playback.current();
        if (item == null || songActionPending) return;
        String id = item.getSubscriptionStoreId();
        String title = item.getTitle();
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(new String[]{"添加收藏", "减少类似推荐", "添加到资料库"}, (dialog, which) ->
                        performSongAction(id, title, which)).setNegativeButton("取消", null).show();
    }

    private void performSongAction(String id, String title, int action) {
        if (songActionPending) return;
        TokenVault.Tokens account = credentials();
        if (!account.authorized()) { setMessage("请先连接 Apple Music。"); return; }
        songActionPending = true;
        songActionMessage = "正在提交“" + title + "”的操作…";
        updatePlayback();
        worker.execute(() -> {
            String result;
            try {
                if (!account.equals(vault.read())) throw new IOException("账号已变更，请重新执行曲目操作。");
                switch (action) {
                    case 0 -> { api.favoriteSong(id); result = "已提交收藏请求；Apple Music 同步后生效。"; }
                    case 1 -> { api.suggestLess(id); result = "已提交减少推荐反馈；不会立即改变当前队列。"; }
                    case 2 -> { api.addSongToLibrary(id); result = "已提交添加资料库请求；同步后生效。"; }
                    default -> throw new IllegalArgumentException("未知曲目操作。");
                }
            } catch (IOException | JSONException | GeneralSecurityException exception) {
                result = "操作失败：" + readable(exception);
            }
            String response = "“" + title + "”： " + result;
            handler.post(() -> {
                if (isDestroyed()) return;
                songActionPending = false;
                songActionMessage = response;
                if (screen.equals("播放")) updatePlayback();
                else new AlertDialog.Builder(this).setTitle("曲目操作").setMessage(response)
                        .setPositiveButton("确定", null).show();
            });
        });
    }

    private void showPlayer() {
        screen = "播放";
        base("播放");
        ScrollView accessible = new ScrollView(this);
        accessible.setFillViewport(true);
        accessible.setOverScrollMode(View.OVER_SCROLL_NEVER);
        accessible.setVerticalScrollBarEnabled(false);
        LinearLayout player = InkUi.column(this);
        player.setPadding(dp(6), dp(8), dp(6), dp(8));
        accessible.addView(player);
        body.addView(accessible, new LinearLayout.LayoutParams(-1, -1));
        playerSource = text("", 12, false);
        InkUi.singleLine(playerSource);
        LinearLayout status = InkUi.row(this);
        status.addView(playerSource, new LinearLayout.LayoutParams(0, dp(28), 1));
        playerState = text("", 11, false);
        playerState.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        status.addView(playerState, new LinearLayout.LayoutParams(dp(76), dp(28)));
        player.addView(status);
        cover = new InkUi.Cover(this);
        int coverSize = getResources().getConfiguration().fontScale > 1.15f ? 128 : 144;
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(dp(coverSize), dp(coverSize));
        artParams.gravity = Gravity.CENTER_HORIZONTAL;
        artParams.setMargins(0, dp(8), 0, dp(12));
        player.addView(cover, artParams);
        playerTitle = text("尚未选择歌曲", 23, true);
        playerTitle.setGravity(Gravity.CENTER);
        playerTitle.setMaxLines(2);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        playerTitle.setPadding(0, 0, 0, dp(8));
        playerArtist = text("", 14, false);
        playerAlbum = text("", 12, false);
        playerArtist.setGravity(Gravity.CENTER);
        playerAlbum.setGravity(Gravity.CENTER);
        InkUi.singleLine(playerArtist); InkUi.singleLine(playerAlbum);
        player.addView(playerTitle);
        player.addView(playerArtist, new LinearLayout.LayoutParams(-1, dp(22)));
        player.addView(playerAlbum, new LinearLayout.LayoutParams(-1, dp(20)));
        chooseMusic = button("去推荐找歌", false, () -> navigate("推荐"));
        player.addView(chooseMusic, new LinearLayout.LayoutParams(-1, dp(48)));
        player.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        seek = new SeekBar(this);
        InkUi.flatSeekBar(seek);
        seek.setContentDescription("播放进度，松手后跳转");
        player.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) elapsed.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { handler.removeCallbacks(ticker); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (playback != null) playback.seek(bar.getProgress());
                handler.postDelayed(ticker, 15000);
            }
        });
        LinearLayout times = InkUi.row(this);
        elapsed = text("0:00", 12, false);
        total = text("0:00", 12, false);
        total.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, dp(24), 1));
        times.addView(total, new LinearLayout.LayoutParams(0, dp(24), 1));
        player.addView(times);
        root.addView(mediaControls(false));
        addNavigation();
        metadataKey = "";
        updatePlayback();
    }

    private void showQueue() {
        screen = "队列";
        base("播放队列");
        rows = InkUi.column(this);
        rows.setPadding(0, dp(12), 0, dp(8));
        body.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        rows.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> view.post(() -> {
            if (isDestroyed() || rows != view || !screen.equals("队列")) return;
            int calculated = Math.max(1, Math.min(12, (view.getHeight() - view.getPaddingTop() - view.getPaddingBottom())
                    / (queueRowHeight() + dp(8))));
            if (calculated != queuePageSize) {
                int index = queuePage * queuePageSize;
                queuePageSize = calculated;
                queuePage = index / queuePageSize;
                renderQueue();
            }
        }));
        pager = InkUi.row(this);
        body.addView(pager, new LinearLayout.LayoutParams(-1, dp(62)));
        addMini();
        addNavigation();
        renderQueue();
    }
    private void renderQueue() {
        if (rows == null || !screen.equals("队列")) return;
        rows.removeAllViews(); pager.removeAllViews();
        List<PlayerQueueItem> queue = playback == null ? List.of() : playback.queue();
        queuePage = Math.min(queuePage, Math.max(0, (queue.size() - 1) / queuePageSize));
        if (queue.isEmpty()) {
            TextView empty = text("队列为空。", 16, false);
            empty.setPadding(0, dp(24), 0, 0);
            rows.addView(empty);
        }
        for (int index = queuePage * queuePageSize; index < Math.min(queue.size(), queuePage * queuePageSize + queuePageSize); index++) {
            PlayerQueueItem item = queue.get(index);
            boolean current = playback.currentQueueId() == item.getPlaybackQueueId();
            String prefix = current ? playback.playing() ? "正在播放 · " : playback.status() + " · " : "";
            Button song = button(prefix + item.getItem().getTitle() + "\n" + item.getItem().getArtistName(),
                    false, () -> {
                        if (playback == null) return;
                        if (current) playback.toggle(); else playback.skip(item.getPlaybackQueueId());
                    });
            InkUi.enabled(song, current || playback.canJump());
            song.setTextSize(14); song.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            song.setMaxLines(3); song.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, queueRowHeight());
            params.bottomMargin = dp(8);
            rows.addView(song, params);
        }
        Button previous = button("上一页", false, () -> { queuePage--; renderQueue(); });
        InkUi.enabled(previous, queuePage > 0);
        pager.addView(previous, new LinearLayout.LayoutParams(dp(84), dp(48)));
        TextView index = text((queuePage + 1) + " / " + Math.max(1, (queue.size() + queuePageSize - 1) / queuePageSize), 12, false);
        index.setGravity(Gravity.CENTER);
        pager.addView(index, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button following = button("下一页", false, () -> { queuePage++; renderQueue(); });
        InkUi.enabled(following, (queuePage + 1) * queuePageSize < queue.size());
        pager.addView(following, new LinearLayout.LayoutParams(dp(84), dp(48)));
        pager.setVisibility(queue.size() <= queuePageSize ? View.GONE : View.VISIBLE);
    }
    private int queueRowHeight() {
        return dp(72 * Math.max(1f, getResources().getConfiguration().fontScale));
    }

    private void updatePlayback() {
        if (!visible) return;
        PlayerMediaItem item = playback == null ? null : playback.current();
        boolean media = playback != null && playback.hasMedia();
        if (mini != null) mini.setVisibility(View.VISIBLE);
        if (miniTitle != null) {
            setText(miniTitle, item == null ? "尚未播放" : item.getTitle());
            setText(miniArtist, playback == null ? "未播放" : playback.status());
            updateMediaButtons(miniMode, miniPrevious, miniPlay, miniNext);
        }
        if (screen.equals("播放") && playerTitle != null) {
            String key = item == null ? "" : item.getSubscriptionStoreId() + "|" + item.getTitle();
            setText(playerTitle, item == null ? "尚未选择歌曲" : item.getTitle());
            setText(playerArtist, item == null ? "" : item.getArtistName());
            setText(playerAlbum, item == null ? "" : item.getAlbumTitle());
            setText(playerSource, playback == null || playback.source().isEmpty() ? "Apple Music" : "来自 · " + playback.source());
            updateMediaButtons(playerMode, playerPrevious, playerPlay, playerNext);
            chooseMusic.setVisibility(media ? View.GONE : View.VISIBLE);
            songActions.setVisibility(item == null ? View.INVISIBLE : View.VISIBLE);
            InkUi.enabled(songActions, item != null && !songActionPending);
            setText(playerState, playback == null ? "未播放" : playback.status());
            updateProgress();
            if (!metadataKey.equals(key) || artwork == null) {
                metadataKey = key;
                loadArtwork(item);
            } else if (cover != null) cover.setBitmap(artwork);
        }
        if (playback != null && !playback.error().isEmpty()) {
            setMessage(playback.error());
            message.setOnClickListener(view -> showPlaybackDiagnostics());
        }
        else if (screen.equals("播放") && !loading) setMessage(songActionMessage);
        if (screen.equals("队列")) renderQueue();
    }
    private void updateMediaButtons(InkUi.IconButton modeButton, InkUi.IconButton previous,
                                    InkUi.IconButton play, InkUi.IconButton nextButton) {
        boolean playing = playback != null && playback.playing();
        play.setIcon(playing ? R.drawable.ic_pause : R.drawable.ic_play, playing ? "暂停" : "播放");
        InkUi.enabled(play, playback != null && (playback.hasMedia() || playback.canRetry()));
        InkUi.enabled(previous, playback != null && playback.canPrevious());
        InkUi.enabled(nextButton, playback != null && playback.canNext());
        String mode = playback == null ? "顺序播放" : playback.mode();
        int modeIcon = switch (mode) {
            case "随机播放" -> R.drawable.ic_shuffle;
            case "单曲循环" -> R.drawable.ic_repeat_one;
            case "列表循环" -> R.drawable.ic_repeat;
            default -> R.drawable.ic_order;
        };
        modeButton.setIcon(modeIcon, mode);
        InkUi.enabled(modeButton, playback != null && playback.hasMedia());
    }
    private void updateProgress() {
        if (seek == null || elapsed == null || total == null) return;
        long duration = playback == null ? 0 : playback.duration();
        int position = (int) Math.min(Integer.MAX_VALUE, playback == null ? 0 : playback.position());
        seek.setMax((int) Math.min(Integer.MAX_VALUE, duration));
        seek.setProgress(position);
        seek.setEnabled(playback != null && playback.canSeek());
        setText(elapsed, formatTime(position));
        setText(total, formatTime(duration));
    }
    private void loadArtwork(PlayerMediaItem item) {
        if (cover == null) return;
        String url = item == null ? "" : item.getArtworkUrl(240, 240);
        if (url == null || url.isEmpty()) { artwork = null; cover.setBitmap(null); return; }
        if (url.equals(artworkKey) && artwork != null) { cover.setBitmap(artwork); return; }
        if (url.equals(failedArtworkKey) || url.equals(artworkKey)) return;
        artworkKey = url;
        artwork = null;
        cover.setBitmap(null);
        worker.execute(() -> {
            try {
                Bitmap bitmap = Artwork.load(url);
                handler.post(() -> {
                    if (isDestroyed() || !artworkKey.equals(url)) return;
                    artwork = bitmap;
                    if (cover != null && screen.equals("播放")) cover.setBitmap(bitmap);
                });
            } catch (IOException exception) {
                handler.post(() -> {
                    if (isDestroyed() || !artworkKey.equals(url)) return;
                    failedArtworkKey = url;
                    artworkKey = "";
                    if (screen.equals("播放")) setMessage("封面加载失败，使用静态占位图；不影响播放。");
                });
            }
        });
    }

    private void showSettings() {
        if (!screen.equals("设置")) {
            if (screen.equals("搜索") && search != null) searchTerm = search.getText().toString();
            View focus = getCurrentFocus();
            if (focus != null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            settingsReturn = screen;
            settingsOrigin = new BrowseState(screen, browseTitle, List.copyOf(items), next, pageAnchor,
                    libraryKind, browsingContainer);
        }
        settingsLayout(SettingsPage.ROOT);
    }

    private void settingsBack() {
        if (settingsPage != SettingsPage.ROOT) {
            settingsLayout(switch (settingsPage) {
                case DISPLAY, ABOUT, ADVANCED -> SettingsPage.MORE;
                default -> SettingsPage.ROOT;
            });
            return;
        }
        if (settingsReturn.equals("播放")) { showPlayer(); return; }
        if (settingsReturn.equals("队列")) { showQueue(); return; }
        if (settingsOrigin != null) {
            screen = settingsOrigin.screen(); browseTitle = settingsOrigin.title(); next = settingsOrigin.next();
            items.clear(); items.addAll(settingsOrigin.items()); pageAnchor = settingsOrigin.anchor();
            libraryKind = settingsOrigin.kind(); browsingContainer = settingsOrigin.container();
            browseLayout();
            if (!credentials().authorized()) connectionPrompt();
        } else navigate(List.of("推荐", "资料库", "搜索").contains(settingsReturn) ? settingsReturn : "推荐");
    }

    private void settingsLayout(SettingsPage page) {
        settingsPage = page;
        screen = "设置";
        base(switch (page) {
            case ROOT -> "设置";
            case ACCOUNT -> "Apple Music";
            case BACKGROUND -> "播放与后台";
            case MORE -> "更多设置";
            case DISPLAY -> "显示与操作";
            case ABOUT -> "关于 BLANK";
            case ADVANCED -> "高级配置";
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout form = InkUi.column(this);
        form.setPadding(0, dp(12), 0, dp(20));
        scroll.addView(form); body.addView(scroll);
        TokenVault.Tokens existing = credentials();
        switch (page) {
            case ROOT -> {
                settingsRow(form, "Apple Music 账号", existing.authorized() ? "已保存授权" : "未连接",
                        () -> settingsLayout(SettingsPage.ACCOUNT));
                settingsRow(form, "播放与后台", "系统权限、播放诊断", () -> settingsLayout(SettingsPage.BACKGROUND));
                settingsRow(form, "更多设置", "显示、关于与高级配置", () -> settingsLayout(SettingsPage.MORE));
            }
            case ACCOUNT -> {
                settingsInfo(form, existing.authorized() ? "已保存授权" : "尚未连接 Apple Music");
                settingsRow(form, "授权 Apple Music", "", this::authorize);
                if (existing.authorized()) {
                    settingsRow(form, "检查账号连接", "", () -> checkAccountConnection(false));
                }
                if (existing.authorized() || !accountError.isEmpty()) {
                    settingsRow(form, "退出账号", "", () -> new AlertDialog.Builder(this)
                            .setTitle("退出账号？").setMessage("清除本机授权，不会删除资料库。")
                            .setNegativeButton("取消", null).setPositiveButton("退出", (dialog, which) -> {
                                try {
                                    if (playback != null) playback.stopPlayback();
                                    vault.clear(); api.reset(); items.clear(); history.clear(); settingsOrigin = null;
                                    settingsLayout(SettingsPage.ACCOUNT); setMessage("本机账号已退出。");
                                } catch (IOException exception) { setMessage(readable(exception)); }
                            }).show());
                }
            }
            case BACKGROUND -> {
                backgroundStatus = settingsInfo(form, "");
                updateBackgroundStatus();
                settingsRow(form, "系统电源设置", "查看 BLANK 的后台与省电限制",
                        () -> openSystemSettings(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                settingsRow(form, "应用系统设置", "海信额外限制需在系统中确认",
                        () -> openSystemSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
                settingsRow(form, "播放诊断", "查看或复制错误记录", this::showPlaybackDiagnostics);
            }
            case MORE -> {
                settingsRow(form, "显示与操作", "", () -> settingsLayout(SettingsPage.DISPLAY));
                settingsRow(form, "关于 BLANK", "", () -> settingsLayout(SettingsPage.ABOUT));
                settingsRow(form, "开发者配置（高级）", "", () -> settingsLayout(SettingsPage.ADVANCED));
            }
            case DISPLAY -> {
                settingsInfo(form, "文字大小跟随系统\n封面静态灰度显示\n无转场、光晕或滚动字幕");
                settingsRow(form, "系统显示设置", "", () -> openSystemSettings(android.provider.Settings.ACTION_DISPLAY_SETTINGS));
            }
            case ABOUT -> settingsInfo(form, "BLANK " + BuildConfig.VERSION_NAME
                    + "\n\n基于官方 MusicKit，并非 Apple 官方应用。\n\n"
                    + "不含电台、离线下载、逐字歌词及个人上传音频；不承诺无损音质。");
            case ADVANCED -> developerSettings(form, existing);
        }
        if (!accountError.isEmpty()) setMessage(accountError);
    }

    private void settingsRow(LinearLayout form, String title, String subtitle, Runnable action) {
        String label = subtitle.isEmpty() ? title : title + "\n" + subtitle;
        Button row = button(label, false, action);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setTextSize(16);
        row.setMaxLines(2);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setContentDescription(title);
        if (!subtitle.isEmpty()) {
            SpannableString copy = new SpannableString(label);
            copy.setSpan(new RelativeSizeSpan(0.78f), title.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            copy.setSpan(new ForegroundColorSpan(Color.DKGRAY), title.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setText(copy);
        }
        form.addView(row, new LinearLayout.LayoutParams(-1,
                dp((subtitle.isEmpty() ? 58 : 78) * Math.max(1f, getResources().getConfiguration().fontScale))));
        InkUi.line(form, Color.LTGRAY);
    }

    private TextView settingsInfo(LinearLayout form, String value) {
        TextView info = text(value, 14, false);
        info.setPadding(dp(4), dp(12), dp(4), dp(20));
        form.addView(info);
        return info;
    }

    private void updateBackgroundStatus() {
        if (backgroundStatus == null) return;
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean restricted = getSystemService(ActivityManager.class).isBackgroundRestricted();
        setText(backgroundStatus, "Android 电池优化：" + (exempt ? "已豁免" : "未豁免")
                + "\n应用后台限制：" + (restricted ? "受限" : "系统未标记受限"));
    }

    private void openSystemSettings(String action) {
        Intent intent = new Intent(action);
        if (action.equals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) {
            intent.setData(Uri.parse("package:" + getPackageName()));
        }
        if (intent.resolveActivity(getPackageManager()) == null) {
            setMessage("当前系统未提供此入口，请从系统设置中查看 BLANK 的权限。");
            return;
        }
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException exception) {
            setMessage("无法打开系统设置，请手动查看 BLANK 的权限。");
        }
    }

    private void developerSettings(LinearLayout form, TokenVault.Tokens existing) {
        String developerStatus;
        try {
            long expiry = DeveloperToken.validate(existing.developer(), System.currentTimeMillis() / 1000);
            developerStatus = "应用接入：已配置 · 有效至 "
                    + Instant.ofEpochSecond(expiry).atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (JSONException exception) {
            developerStatus = existing.developer().isEmpty()
                    ? "应用接入：未配置"
                    : "应用接入不可用：" + readable(exception);
        }
        settingsInfo(form, developerStatus + "\n仅接受签名 JWT，不接受密码或私钥。");
        EditText developer = new EditText(this);
        developer.setTextColor(Color.BLACK); developer.setHintTextColor(Color.DKGRAY);
        developer.setTextSize(14); developer.setSingleLine(true);
        developer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        developer.setAutofillHints((String[]) null);
        developer.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        developer.setHint(existing.developer().isEmpty() ? "粘贴 Developer Token（JWT）" : "已配置；留空保留现有 Token");
        developer.setBackground(InkUi.underline(this, true));
        developer.setPadding(dp(10), dp(10), dp(10), dp(10));
        developer.setContentDescription("Developer Token");
        form.addView(developer, new LinearLayout.LayoutParams(-1, dp(52)));
        form.addView(spaced(button("保存 Developer Token", false, () -> {
            String input = developer.getText().toString().trim();
            try {
                TokenVault.Tokens old = vault.read();
                String token = input.isEmpty() ? old.developer() : input;
                DeveloperToken.validate(token, System.currentTimeMillis() / 1000);
                if (!token.equals(old.developer()) && playback != null) playback.stopPlayback();
                vault.save(new TokenVault.Tokens(token, token.equals(old.developer()) ? old.user() : ""));
                api.reset();
                settingsLayout(SettingsPage.ADVANCED);
                setMessage(token.equals(old.developer()) && !old.user().isEmpty()
                        ? "Developer Token 已保存，原有用户授权已保留。"
                        : "Developer Token 已加密保存。下一步：授权 Apple Music。");
            } catch (IOException | JSONException | GeneralSecurityException exception) {
                setMessage("保存失败：" + readable(exception));
            }
        })));
    }

    private void showPlaybackDiagnostics() {
        if (playback == null) { setMessage("播放器尚未连接，请稍后重试。"); return; }
        String report = playback.diagnostics();
        new AlertDialog.Builder(this).setTitle("播放诊断").setMessage(report)
                .setNegativeButton("关闭", null)
                .setNeutralButton("复制诊断", (dialog, which) -> {
                    getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                            android.content.ClipData.newPlainText("BLANK 播放诊断", report));
                    setMessage("已复制播放状态，不含账号或 Token。");
                })
                .setPositiveButton("重建播放器", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("重新载入最近播放内容？")
                        .setMessage("释放当前播放器并重新准备上次选择的歌曲或专辑；进度和之后追加的队列不会保留。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("重建", (confirm, action) -> {
                            if (playback != null) { playback.rebuildPlayback(); showPlayer(); }
                            else setMessage("播放器已断开，请重新选择歌曲。");
                        }).show()).show();
    }

    private void authorize() {
        if (authorizationPending) return;
        try {
            TokenVault.Tokens tokens = vault.read();
            if (tokens.developer().isEmpty()) { setMessage("此安装包尚未配置接入凭证。"); return; }
            DeveloperToken.validate(tokens.developer(), System.currentTimeMillis() / 1000);
            PackageManager packages = getPackageManager();
            if (packages.getPackageInfo("com.apple.android.music", 0).getLongVersionCode() < APPLE_MUSIC_MIN_VERSION) {
                setMessage("请先更新官方 Apple Music。"); return;
            }
            Intent route = new Intent(Intent.ACTION_VIEW, Uri.parse("musicsdk://applemusic/authenticate-v1"))
                    .setPackage("com.apple.android.music");
            if (packages.resolveActivity(route, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                setMessage("Apple Music 未提供授权入口，请更新官方 App。"); return;
            }
            Intent intent = authorizationIntent(tokens.developer());
            authorizationPending = true;
            setMessage("正在打开 Apple Music…");
            startActivityForResult(intent, AUTH_REQUEST);
        } catch (PackageManager.NameNotFoundException exception) {
            authorizationPending = false;
            setMessage("未安装官方 Apple Music。");
        } catch (IOException | JSONException | GeneralSecurityException | android.content.ActivityNotFoundException exception) {
            authorizationPending = false;
            setMessage("无法开始授权：" + readable(exception));
        }
    }
    Intent authorizationIntent(String developer) {
        authentication = AuthenticationFactory.createAuthenticationManager(this);
        return authentication.createIntentBuilder(developer).setHideStartScreen(true).build();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != AUTH_REQUEST) return;
        authorizationPending = false;
        if (data == null) { setMessage("授权已取消。"); return; }
        if (authentication == null) authentication = AuthenticationFactory.createAuthenticationManager(this);
        TokenResult tokenResult = authentication.handleTokenResult(data);
        if (tokenResult.isError()) {
            setMessage(switch (tokenResult.getError()) {
                case USER_CANCELLED -> "授权已取消。";
                case NO_SUBSCRIPTION -> "此账号没有 Apple Music 订阅。";
                case SUBSCRIPTION_EXPIRED -> "Apple Music 订阅已过期。";
                case TOKEN_FETCH_ERROR -> "获取授权失败，请在官方 App 确认登录后重试。";
                case UNKNOWN -> "授权未完成，请重试或更新官方 App。";
            });
            return;
        }
        if (result != RESULT_OK) { setMessage("授权未完成，请重试。"); return; }
        String userToken = tokenResult.getMusicUserToken();
        if (userToken == null || userToken.isBlank()) { setMessage("Apple 未返回用户 Token，请重试。"); return; }
        try {
            TokenVault.Tokens old = vault.read();
            if (playback != null) playback.stopPlayback();
            vault.save(new TokenVault.Tokens(old.developer(), userToken));
            api.reset();
            showSettings();
            checkAccountConnection(true);
        } catch (IOException | JSONException | GeneralSecurityException exception) {
            setMessage("无法保存授权：" + readable(exception));
        }
    }

    private void checkAccountConnection(boolean openRecommendations) {
        if (loading) return;
        if (!credentials().authorized()) { setMessage("请先完成 Apple Music 官方授权。"); return; }
        int generation = ++requestGeneration;
        loading = true;
        setMessage("正在向 Apple 验证账号连接…");
        worker.execute(() -> {
            try {
                api.reset();
                String storefront = api.storefront();
                handler.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    loading = false;
                    if (openRecommendations) navigate("推荐");
                    else setMessage("账号连接正常 · " + storefront.toUpperCase(Locale.ROOT));
                });
            } catch (IOException | JSONException | GeneralSecurityException exception) {
                handler.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    loading = false;
                    setMessage("账号连接未通过：" + readable(exception));
                });
            }
        });
    }

    private Button spaced(Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(12); button.setLayoutParams(params); return button;
    }
    private int dp(float value) { return InkUi.dp(this, value); }
    private TextView text(String value, float size, boolean bold) { return InkUi.text(this, value, size, bold); }
    private Button button(String value, boolean selected, Runnable action) { return InkUi.button(this, value, selected, action); }
    private static void setText(TextView view, String text) {
        String safe = text == null ? "" : text;
        if (!view.getText().toString().equals(safe)) view.setText(safe);
    }
    static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private static String readable(Exception exception) {
        android.util.Log.w("Moting", "Operation failed (" + exception.getClass().getSimpleName() + "); message shown.");
        return UserMessages.readable(exception);
    }
}
