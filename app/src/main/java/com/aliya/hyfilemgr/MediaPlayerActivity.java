package com.aliya.hyfilemgr;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.os.Environment;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.aliya.hyfilemgr.ui.UiKit;
import com.aliya.hyfilemgr.player.PlayerCore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置播放器（Media3 / ExoPlayer 统一内核）。
 *
 * <p>改造前用原生 {@code VideoView}，注释自承「本页只处理视频，音频走文件管理的弹窗播放器
 * ——{@code VideoView} 依赖 Surface，把 View 隐藏后音频无输出」。现在换成 {@link ExoPlayer}：
 * 它**不依赖 Surface**，音频可直接出声，于是音视频共用同一个内核。</p>
 *
 * <p>另一项关键收益：**能注入请求头**（{@link PlayerCore#create}）。实测采集到的番剧源
 * 几乎都要求特定 User-Agent / Referer / Cookie，而 {@code VideoView} 连 headers 重载都没有，
 * 且 {@code MediaPlayer} 的 headers 对 HLS 分片不生效。</p>
 *
 * <p>支持**同目录左右切换**：打开时收到当前目录下的音视频列表与起始索引，
 * 点「上一个 / 下一个」切换并自动播放（列表由文件管理收集同目录同类型文件）。</p>
 *
 * <p>新增：{@link #EXTRA_HEADERS} 可随 Intent 传入请求头（网络播放用），
 * 以及直接播放 http(s) 地址（在线源用）。</p>
 */
public class MediaPlayerActivity extends Activity {

    /** 应用级 DPI 覆盖：让「设置 → 显示密度」只对本应用生效（见 DpiUtils） */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.aliya.hyfilemgr.util.DpiUtils.wrap(base));
    }

    public static final String EXTRA_PATH = "media_path";     // 兼容单文件调用
    public static final String EXTRA_URI = "media_uri";       // 兼容单文件调用
    public static final String EXTRA_TITLE = "media_title";
    public static final String EXTRA_LIST = "media_list";     // String[] 播放列表
    public static final String EXTRA_INDEX = "media_index";   // 起始索引
    public static final String EXTRA_IS_URI = "media_is_uri"; // 列表项是否为 content://
    /** 请求头 Bundle（网络播放用）。键值均为 String。 */
    public static final String EXTRA_HEADERS = "media_headers";
    /** 观看历史的业务键（动漫模块传「集页面地址」），用于回写进度 */
    public static final String EXTRA_HISTORY_KEY = "media_history_key";
    /** 续播起点（毫秒） */
    public static final String EXTRA_START_MS = "media_start_ms";
    /** B站番剧 season_id（取弹幕用，<=0 表示不加载弹幕） */
    public static final String EXTRA_DANMAKU_SEASON = "media_danmaku_season";
    /** 集序号（1 起） */
    public static final String EXTRA_DANMAKU_EP = "media_danmaku_ep";

    // ── 动漫模式（播放器内选集用；参考 Kazumi 的选集面板）──
    /** 集标题数组 */
    public static final String EXTRA_EP_TITLES = "media_ep_titles";
    /** 集页面地址数组（切集时用它重新解析拿直链） */
    public static final String EXTRA_EP_URLS = "media_ep_urls";
    /** 当前集下标 */
    public static final String EXTRA_EP_INDEX = "media_ep_index";
    /** 源配置 JSON（切集解析用） */
    public static final String EXTRA_SOURCE_JSON = "media_source_json";
    /** 线路名 */
    public static final String EXTRA_CHANNEL_NAME = "media_channel_name";
    /** 显示标题（番剧名 · 线路） */
    public static final String EXTRA_DISPLAY_TITLE = "media_display_title";

    /**
     * 构造「播放单个网络地址」的 Intent —— 在线源（番剧/远程媒体）走这里。
     *
     * <p>本页原有的调用方 {@code FileManagerModule.openWithBuiltInPlayer} 是为**本地文件列表**
     * 设计的（会收集同目录文件做左右切换），网络地址没有"同目录"可言，所以单开这个入口。
     * 二者共用同一个 Media3 内核。</p>
     *
     * @param headers 请求头（Referer / User-Agent / Cookie 等），可为 null。
     *                注意：这些头会作用于**播放列表与全部分片请求**（见 PlayerCore 的说明）。
     */
    public static android.content.Intent urlIntent(android.content.Context ctx, String url,
                                                   String title, Map<String, String> headers) {
        return urlIntent(ctx, url, title, headers, null, 0L);
    }

    /**
     * 带观看历史键与续播起点的版本（动漫模块专用：key = 集页面地址）。
     *
     * @param historyKey 用于回写进度；null 表示不记录（普通文件播放）
     * @param startMs    续播起点，0 表示从头
     */
    public static android.content.Intent urlIntent(android.content.Context ctx, String url,
                                                   String title, Map<String, String> headers,
                                                   String historyKey, long startMs) {
        return urlIntent(ctx, url, title, headers, historyKey, startMs, null, null, -1, null, null);
    }

    /**
     * 完整版（动漫模块用）：额外带上整条线路的集列表与源配置，
     * 这样**播放器内部就能选集**，不必退回动漫页。
     */
    public static android.content.Intent urlIntent(android.content.Context ctx, String url,
                                                   String title, Map<String, String> headers,
                                                   String historyKey, long startMs,
                                                   String[] epTitles, String[] epUrls, int epIndex,
                                                   String sourceJson, String channelName) {
        android.content.Intent i = new android.content.Intent(ctx, MediaPlayerActivity.class);
        i.putExtra(EXTRA_URI, url);
        i.putExtra(EXTRA_IS_URI, true);
        i.putExtra(EXTRA_TITLE, title);
        if (headers != null && !headers.isEmpty()) {
            Bundle b = new Bundle();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    b.putString(e.getKey(), e.getValue());
                }
            }
            i.putExtra(EXTRA_HEADERS, b);
        }
        if (historyKey != null && !historyKey.isEmpty()) i.putExtra(EXTRA_HISTORY_KEY, historyKey);
        if (startMs > 0) i.putExtra(EXTRA_START_MS, startMs);
        if (epTitles != null && epUrls != null && epTitles.length == epUrls.length && epTitles.length > 0) {
            i.putExtra(EXTRA_EP_TITLES, epTitles);
            i.putExtra(EXTRA_EP_URLS, epUrls);
            i.putExtra(EXTRA_EP_INDEX, epIndex);
            i.putExtra(EXTRA_DISPLAY_TITLE, title);
            if (sourceJson != null) i.putExtra(EXTRA_SOURCE_JSON, sourceJson);
            if (channelName != null) i.putExtra(EXTRA_CHANNEL_NAME, channelName);
        }
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf"));

    private PlayerView playerView;
    private ExoPlayer player;
    private View placeholder, controls, topBar;
    private ImageView btnPlay, btnPrev, btnNext;
    private SeekBar seekBar;
    private TextView tvPos, tvDur, tvTitle, tvCounter, tvAudioName;
    /** 播放器增强（对齐 Kazumi）：倍速与画面比例用文字按钮循环切换 */
    private TextView tvSpeed, tvRatio;
    // ── 弹幕（基础版：B站弹幕源 + 自绘引擎）──
    private com.aliya.hyfilemgr.player.DanmakuView danmakuView;
    private TextView btnDanmaku;
    private boolean danmakuOn = false;
    private long danmakuSeasonId = -1;
    private int danmakuEpNo = 1;
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private static final String[] SPEED_LABELS = {"1.0×", "1.25×", "1.5×", "2.0×", "0.5×", "0.75×"};
    private static final int[] RATIOS = {
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    };
    private static final String[] RATIO_LABELS = {"适应", "拉伸", "裁剪"};
    private int speedIdx = 0;
    private int ratioIdx = 0;

    // ── 手势（对齐 Kazumi/主流播放器）：水平快进、左半亮度、右半音量、双击暂停、长按加速 ──
    private GestureDetector gesture;

    // ── 动漫模式：播放器内选集 ──
    private boolean animeMode = false;
    private String[] epTitles, epUrls;
    private int epIndex = 0;
    private String sourceJson, channelName, displayTitle;
    private LinearLayout errorOverlay;
    private TextView tvError;
    private TextView btnEpisodes;

    // ── 观看历史（续播 + 进度回写）──
    private com.aliya.hyfilemgr.anime.WatchHistory watchHistory;
    private String historyKey;
    private long pendingStartMs = 0L;
    private final Handler historyHandler = new Handler(Looper.getMainLooper());
    private final Runnable historyTick = new Runnable() {
        @Override public void run() {
            saveHistoryProgress();
            historyHandler.postDelayed(this, 5000L);
        }
    };
    private float downX, downY;
    private boolean horizontalMode, verticalMode;
    private boolean longPressing;
    private long seekTargetMs = -1;

    private String[] playlist;
    private int idx = 0;
    private boolean isUri = false;
    private Map<String, String> headers;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging = false;
    private boolean controlsVisible = true;
    /** 是否已进入可播状态（对应原 VideoView 的 prepared 语义） */
    private boolean prepared = false;
    /** 标记"是我们主动切换"的加载，用于区分播放结束与出错 */
    private boolean switching = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            syncProgress();
            syncDanmakuTime();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_media_player);

        // 弹幕季 id / 集数：此前从未读取 extra，导致 initDanmaku() 首行
        // (danmakuSeasonId <= 0) 直接 return，弹幕永远是 0 条
        danmakuSeasonId = getIntent().getIntExtra(EXTRA_DANMAKU_SEASON, -1);
        danmakuEpNo = getIntent().getIntExtra(EXTRA_DANMAKU_EP, 1);
        playerView = findViewById(R.id.video_view);
        placeholder = findViewById(R.id.audio_placeholder);
        controls = findViewById(R.id.player_controls);
        topBar = findViewById(R.id.player_top);
        btnPlay = findViewById(R.id.btn_player_play);
        btnPrev = findViewById(R.id.btn_player_prev);
        btnNext = findViewById(R.id.btn_player_next);
        ImageView btnBack = findViewById(R.id.btn_player_back);
        seekBar = findViewById(R.id.seek_player);
        tvPos = findViewById(R.id.tv_player_pos);
        tvDur = findViewById(R.id.tv_player_dur);
        tvTitle = findViewById(R.id.tv_player_title);
        tvCounter = findViewById(R.id.tv_player_counter);
        tvAudioName = findViewById(R.id.tv_audio_name);
        View root = findViewById(R.id.player_root);

        // ── 请求头（网络源需要）──
        // 动漫模式：带上整条线路的集列表，播放器内即可选集
        epTitles = getIntent().getStringArrayExtra(EXTRA_EP_TITLES);
        epUrls = getIntent().getStringArrayExtra(EXTRA_EP_URLS);
        epIndex = getIntent().getIntExtra(EXTRA_EP_INDEX, 0);
        sourceJson = getIntent().getStringExtra(EXTRA_SOURCE_JSON);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        displayTitle = getIntent().getStringExtra(EXTRA_DISPLAY_TITLE);
        animeMode = epTitles != null && epUrls != null && epTitles.length == epUrls.length
                && epTitles.length > 0;

        // 观看历史：续播起点 + 进度回写键
        historyKey = getIntent().getStringExtra(EXTRA_HISTORY_KEY);
        pendingStartMs = getIntent().getLongExtra(EXTRA_START_MS, 0L);
        if (historyKey != null && !historyKey.isEmpty()) {
            watchHistory = new com.aliya.hyfilemgr.anime.WatchHistory(this);
        }

        Bundle hb = getIntent().getBundleExtra(EXTRA_HEADERS);
        if (hb != null && !hb.isEmpty()) {
            headers = new HashMap<>();
            for (String k : hb.keySet()) {
                String v = hb.getString(k);
                if (k != null && v != null) headers.put(k, v);
            }
        }

        // ── 组装播放列表（优先取列表，兼容旧的单文件传参）──
        String[] list = getIntent().getStringArrayExtra(EXTRA_LIST);
        int startIdx = getIntent().getIntExtra(EXTRA_INDEX, 0);
        isUri = getIntent().getBooleanExtra(EXTRA_IS_URI, false);
        if (list == null || list.length == 0) {
            String path = getIntent().getStringExtra(EXTRA_PATH);
            String uriStr = getIntent().getStringExtra(EXTRA_URI);
            String single = (path != null && !path.isEmpty()) ? path : uriStr;
            if (single == null || single.isEmpty()) {
                UiKit.toast(this, "缺少播放地址");
                finish();
                return;
            }
            isUri = (uriStr != null && !uriStr.isEmpty());
            list = new String[]{single};
            startIdx = 0;
        }
        playlist = list;
        idx = Math.max(0, Math.min(startIdx, playlist.length - 1));

        // ── 创建统一内核（headers 在这里生效，作用于播放列表与全部分片请求）──
        player = PlayerCore.create(this, headers);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    prepared = true;
                    switching = false;
                    hideError();
                    // 续播定位（只做一次）
                    if (pendingStartMs > 0) {
                        try {
                            player.seekTo(pendingStartMs);
                        } catch (Throwable ignored) {
                        }
                        pendingStartMs = 0L;
                    }
                    // 启动进度回写（每 5 秒）
                    historyHandler.removeCallbacks(historyTick);
                    if (watchHistory != null) historyHandler.postDelayed(historyTick, 5000L);
                    int dur = (int) Math.max(0, player.getDuration());
                    seekBar.setMax(dur);
                    tvDur.setText(fmt(dur));
                    updatePlayIcon(player.isPlaying());
                    handler.removeCallbacks(ticker);
                    handler.post(ticker);
                    syncDanmakuTime();
                    if (player.getPlayWhenReady() && !player.isPlaying()) {
                        // READY 但未播（如暂停后的 seek），保持用户意图不动
                    }
                } else if (state == Player.STATE_ENDED) {
                    updatePlayIcon(false);
                    // 循环列表：播完自动下一个，最后一个播完回到第一个
                    playAt(idx < playlist.length - 1 ? idx + 1 : 0);
                } else if (state == Player.STATE_BUFFERING) {
                    if (!prepared) tvDur.setText("缓冲中…");
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                switching = false;
                prepared = false;
                String msg = error.getErrorCodeName();
                // 打日志：下次出问题能直接从 logcat 定位（含 cause 链与当前 URL）
                try {
                    StringBuilder sb = new StringBuilder("playback error: ").append(msg)
                            .append(" | media=").append(
                                    (playlist != null && idx >= 0 && idx < playlist.length)
                                            ? playlist[idx] : "?")
                            .append(" | headers=").append(headers == null ? "none" : headers.keySet());
                    Throwable c = error.getCause();
                    int depth = 0;
                    while (c != null && depth++ < 4) {
                        sb.append(" <- ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
                        c = c.getCause();
                    }
                    android.util.Log.e("HYVQ_PLAYER", sb.toString());
                } catch (Throwable ignored) {
                }
                tvDur.setText("无法播放");
                // 内嵌显示（不再弹 Toast），并给出可读的说明
                showError(friendlyError(msg), error);
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayIcon(isPlaying);
            }
        });

        setupGestures(root);
        btnBack.setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> togglePlay());
        btnPrev.setOnClickListener(v -> step(-1));
        btnNext.setOnClickListener(v -> step(1));
        ImageView btnList = findViewById(R.id.btn_player_list);
        if (btnList != null) btnList.setOnClickListener(v -> showPlaylistPicker());

        // ── 弹幕（基础版）──
        initDanmaku();

        // 内嵌错误浮层（用户要求：不要用弹出的界面）
        errorOverlay = findViewById(R.id.player_error_overlay);
        tvError = findViewById(R.id.tv_player_error);
        TextView btnRetry = findViewById(R.id.btn_player_retry);
        if (btnRetry != null) btnRetry.setOnClickListener(v -> {
            hideError();
            if (playlist != null && idx >= 0 && idx < playlist.length) playAt(idx);
        });

        // 选集按钮：仅动漫模式可用（参考 Kazumi 的选集）
        btnEpisodes = findViewById(R.id.btn_player_episodes);
        if (btnEpisodes != null) {
            btnEpisodes.setVisibility(animeMode ? View.VISIBLE : View.GONE);
            if (animeMode) btnEpisodes.setOnClickListener(v -> showEpisodePicker());
        }

        // ── 播放器增强：倍速 / 画面比例 / 截图 ──
        tvSpeed = findViewById(R.id.btn_player_speed);
        tvRatio = findViewById(R.id.btn_player_ratio);
        if (tvSpeed != null) tvSpeed.setOnClickListener(v -> showSpeedPicker());
        if (tvRatio != null) tvRatio.setOnClickListener(v -> cycleRatio());
        if (tvSpeed != null) tvSpeed.setOnLongClickListener(v -> { captureFrame(); return true; });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) tvPos.setText(fmt(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (prepared) player.seekTo(sb.getProgress());
            }
        });

        playAt(idx);
    }

    /** 切到第 i 个并自动播放 */
    private void playAt(int i) {
        // 切换媒体后 ExoPlayer 会保留 playbackSpeed，按钮文案无需重置
        if (playlist == null || i < 0 || i >= playlist.length || player == null) return;
        idx = i;
        final String path = playlist[i];
        String name = baseName(path);
        // 动漫模式：显示「番剧名 · 线路 · 第N集」，而不是 index.m3u8 这种地址末段
        if (animeMode && epIndex >= 0 && epIndex < epTitles.length) {
            String ep = epTitles[epIndex];
            String head = displayTitle == null || displayTitle.isEmpty()
                    ? "" : displayTitle + " · ";
            tvTitle.setText(head + (ep == null || ep.isEmpty() ? ("第 " + (epIndex + 1) + " 集") : ep));
            tvCounter.setText((epIndex + 1) + " / " + epTitles.length);
        } else {
            tvTitle.setText(name);
            tvCounter.setText((i + 1) + " / " + playlist.length);
        }

        boolean isVideo = isVideoFile(name);
        placeholder.setVisibility(isVideo ? View.GONE : View.VISIBLE);
        playerView.setVisibility(isVideo ? View.VISIBLE : View.INVISIBLE);
        if (!isVideo) tvAudioName.setText(name);

        prepared = false;
        switching = true;
        updatePlayIcon(false);
        seekBar.setProgress(0);
        seekBar.setMax(0);
        tvPos.setText("00:00");
        tvDur.setText("00:00");
        btnPrev.setAlpha(i > 0 ? 1f : 0.35f);
        btnNext.setAlpha(i < playlist.length - 1 ? 1f : 0.35f);

        try {
            // 外挂字幕（对齐 Kazumi）：优先用 ExoPlayer 内建字幕轨道，
            // 支持 SRT / SSA(ASS) / VTT；ASS 特效需 libass，本项目不做。
            androidx.media3.common.MediaItem mi;
            List<androidx.media3.common.MediaItem.SubtitleConfiguration> subs =
                    findSubtitles(path, isUri);
            androidx.media3.common.MediaItem base = PlayerCore.mediaItem(path, isUri);
            if (base == null) {
                UiKit.toast(this, "无效的播放地址");
                return;
            }
            if (!subs.isEmpty()) {
                mi = base.buildUpon().setSubtitleConfigurations(subs).build();
                UiKit.toast(this, "已加载 " + subs.size() + " 条字幕");
            } else {
                mi = base;
            }
            player.setMediaItem(mi);
            player.setPlayWhenReady(true);
            player.prepare();
        } catch (Throwable t) {
            UiKit.toast(this, "打开失败：" + t.getMessage());
        }
    }

    /** 左下角「选择文件」：列出当前播放列表（当前目录的音视频文件），点选即切换播放 */
    private void showPlaylistPicker() {
        if (playlist == null || playlist.length == 0) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(this, "播放列表（" + playlist.length + "）"));

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sc.addView(col);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().density * 300));
        box.addView(sc, slp);

        final android.app.Dialog d = UiKit.glassDialog(this, box);
        int primary = UiKit.color(this, com.google.android.material.R.attr.colorPrimary);
        int normal = UiKit.color(this, com.google.android.material.R.attr.colorOnSurface);
        int pad = (int) (getResources().getDisplayMetrics().density * 10);

        for (int i = 0; i < playlist.length; i++) {
            final int index = i;
            TextView tv = new TextView(this);
            tv.setText((i + 1) + ".  " + baseName(playlist[i]));
            tv.setTextSize(13);
            tv.setPadding(pad, pad, pad, pad);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tv.setTextColor(i == idx ? primary : normal);
            if (i == idx) tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setOnClickListener(v -> {
                d.dismiss();
                playAt(index);
            });
            col.addView(tv);
        }
        d.show();
    }

    /** 左右切换（循环）：首尾相接，不做越界提示打断 */
    private void step(int delta) {
        if (playlist == null || playlist.length == 0) return;
        int n = idx + delta;
        if (n < 0) n = playlist.length - 1;
        if (n >= playlist.length) n = 0;
        playAt(n);
    }

    private static String baseName(String p) {
        if (p == null) return "";
        int s = p.lastIndexOf('/');
        return (s >= 0 && s < p.length() - 1) ? p.substring(s + 1) : p;
    }

    private boolean isVideoFile(String name) {
        if (name == null) return true;
        // 网络地址常带 ?query#fragment，必须先剥离——
        // 否则 "a.m3u8?token=x" 取到的后缀是 "m3u8?token=x"，落不进白名单，视频会被误判成音频。
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        int h = name.indexOf('#');
        if (h >= 0) name = name.substring(0, h);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return true;
        String ext = name.substring(dot + 1).toLowerCase();
        // 流媒体后缀也按视频处理
        if ("m3u8".equals(ext) || "mpd".equals(ext)) return true;
        return VIDEO_EXTS.contains(ext);
    }

    private void togglePlay() {
        if (player == null) return;
        if (!prepared) {
            playAt(idx);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            updatePlayIcon(false);
        } else {
            player.play();
            updatePlayIcon(true);
        }
    }

    private void updatePlayIcon(boolean playing) {
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        controls.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        topBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
    }

    private void syncProgress() {
        if (!prepared || dragging || player == null) return;
        try {
            int pos = (int) Math.max(0, player.getCurrentPosition());
            seekBar.setProgress(pos);
            tvPos.setText(fmt(pos));
            updatePlayIcon(player.isPlaying());
        } catch (Throwable ignored) {
        }
    }

    /** 手势区（对齐 Kazumi/主流播放器）：水平快进、左半亮度、右半音量、双击暂停、长按加速 */
    private void setupGestures(final View root) {
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                togglePlay();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                // 长按临时加速到 2×，松手恢复（Kazumi 同款）
                longPressing = true;
                try {
                    if (player != null) player.setPlaybackSpeed(2.0f);
                    if (tvSpeed != null) tvSpeed.setText("2.0×");
                } catch (Throwable ignored) {
                }
            }

            @Override public boolean onDown(MotionEvent e) {
                downX = e.getX();
                downY = e.getY();
                horizontalMode = verticalMode = false;
                seekTargetMs = -1;
                return true;
            }
        });

        root.setOnTouchListener((v, ev) -> {
            gesture.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!horizontalMode && !verticalMode) {
                        if (Math.abs(dx) > dpInt(12) || Math.abs(dy) > dpInt(12)) {
                            horizontalMode = Math.abs(dx) > Math.abs(dy);
                            verticalMode = !horizontalMode;
                        }
                    }
                    if (horizontalMode && prepared) {
                        // 全屏宽 = 2 分钟；滑动中先预览目标时间，抬手才真正 seek
                        float ratio = dx / Math.max(1f, v.getWidth());
                        long delta = (long) (ratio * 120_000L);
                        long target = Math.max(0, Math.min(player.getDuration(),
                                player.getCurrentPosition() + delta));
                        seekTargetMs = target;
                        tvPos.setText(fmt((int) target) + "  ⟶");
                    } else if (verticalMode) {
                        float ratio = -dy / Math.max(1f, v.getHeight());
                        if (downX < v.getWidth() / 2f) {
                            adjustBrightness(ratio * 1.2f);
                        } else {
                            adjustVolume(ratio * 1.2f);
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (longPressing) {
                        longPressing = false;
                        try {
                            if (player != null) player.setPlaybackSpeed(SPEEDS[speedIdx]);
                            if (tvSpeed != null) tvSpeed.setText(SPEED_LABELS[speedIdx]);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (horizontalMode && seekTargetMs >= 0 && prepared) {
                        try {
                            player.seekTo(seekTargetMs);
                        } catch (Throwable ignored) {
                        }
                        seekTargetMs = -1;
                    }
                    horizontalMode = verticalMode = false;
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    private void adjustBrightness(float delta) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            float cur = lp.screenBrightness;
            if (cur < 0) cur = 0.5f;              // -1 表示跟随系统
            cur = Math.max(0.01f, Math.min(1f, cur + delta));
            lp.screenBrightness = cur;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    private void adjustVolume(float delta) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int next = Math.max(0, Math.min(max, cur + Math.round(delta * max)));
            if (next != cur) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private int dpInt(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    // ══════════════ 弹幕（基础版） ══════════════

    /** 初始化弹幕：仅当有 season_id 时才展示开关并拉取弹幕 */
    private void initDanmaku() {
        danmakuView = findViewById(R.id.danmaku_view);
        btnDanmaku = findViewById(R.id.btn_player_danmaku);
        if (danmakuView == null || btnDanmaku == null) return;
        if (danmakuSeasonId <= 0) return;        // 无 B站 元数据 → 不展示入口

        btnDanmaku.setVisibility(View.VISIBLE);
        // 读用户偏好（主设置可改默认值）
        try {
            danmakuOn = getSharedPreferences("anime_prefs", MODE_PRIVATE)
                    .getBoolean("danmaku_default_on", true);
        } catch (Throwable ignored) {
        }
        if (danmakuOn) {
            btnDanmaku.setText("弹幕·开");
            btnDanmaku.setTextColor(0xFF00E676);
        }
        btnDanmaku.setOnClickListener(v -> {
            danmakuOn = !danmakuOn;
            // 记住用户选择（覆盖默认）
            getSharedPreferences("anime_prefs", MODE_PRIVATE)
                    .edit().putBoolean("danmaku_default_on", danmakuOn).apply();
            btnDanmaku.setText(danmakuOn ? "弹幕·开" : "弹幕");
            btnDanmaku.setTextColor(danmakuOn
                    ? 0xFF00E676 : android.graphics.Color.WHITE);
            danmakuView.setEnabled(danmakuOn);
            if (danmakuOn && danmakuView.count() == 0) loadDanmaku();
            syncDanmakuTime();
        });

        // 后台预取弹幕（不阻塞起播）
        new Thread(this::loadDanmaku, "danmaku-fetch").start();
    }

    private void loadDanmaku() {
        try {
            java.util.List<com.aliya.hyfilemgr.player.DanmakuView.Danmaku> list =
                    com.aliya.hyfilemgr.anime.BilibiliDanmaku.fetch(danmakuSeasonId, danmakuEpNo);
            final int n = list == null ? 0 : list.size();
            runOnUiThread(() -> {
                if (danmakuView == null) return;
                if (list != null) danmakuView.setDanmaku(list);
                if (n > 0) {
                    danmakuView.setEnabled(danmakuOn);
                    if (btnDanmaku != null && !danmakuOn) {
                        btnDanmaku.setText("弹幕(" + n + ")");
                    }
                } else if (btnDanmaku != null) {
                    btnDanmaku.setText("弹幕·无");
                }
                syncDanmakuTime();
            });
        } catch (Throwable ignored) {
        }
    }

    /** 把播放进度同步给弹幕引擎（每次 ticker / 状态变化都调） */
    private void syncDanmakuTime() {
        if (danmakuView == null || player == null) return;
        try {
            danmakuView.syncTime(player.getCurrentPosition(), player.isPlaying());
        } catch (Throwable ignored) {
        }
    }

    /** 把当前进度回写给观看历史（播放器与动漫模块之间靠 historyKey 解耦） */
    private void saveHistoryProgress() {
        if (watchHistory == null || historyKey == null || player == null) return;
        try {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            long safeDur = dur == androidx.media3.common.C.TIME_UNSET ? 0L : dur;
            watchHistory.updateProgress(historyKey, pos, safeDur);
        } catch (Throwable ignored) {
        }
    }

    // ══════════════ 播放错误：内嵌显示（不用 Toast） ══════════════

    private void showError(String msg, PlaybackException err) {
        if (errorOverlay == null) return;
        errorOverlay.setVisibility(View.VISIBLE);
        if (tvError != null) {
            String code = err == null ? null : err.getErrorCodeName();
            tvError.setText(msg + (code == null || code.isEmpty() ? "" : "\n\n" + code));
        }
    }

    private void hideError() {
        if (errorOverlay != null) errorOverlay.setVisibility(View.GONE);
    }

    /** 把 ExoPlayer 的错误码翻译成用户能懂的话（原始码一并显示在下方） */
    private String friendlyError(String code) {
        if (code == null) return "播放失败。";
        if (code.contains("PARSING_CONTAINER_UNSUPPORTED")) {
            return "服务器返回的内容不是视频流。\n"
                    + "常见原因：源站防盗链（缺 Referer）、直链已过期，\n"
                    + "或该地址其实是个网页。建议换一条线路。";
        }
        if (code.contains("PARSING_MANIFEST_UNSUPPORTED")) {
            return "播放列表格式无法解析（可能是加密或私有格式的 m3u8）。";
        }
        if (code.contains("DECODING")) {
            return "解码失败：该视频的编码格式本机不支持\n（常见于 AV1 或 HEVC 10bit）。";
        }
        if (code.contains("NETWORK") || code.contains("IO")) {
            return "网络读取失败，直链可能已过期。\n建议换线路，或退回动漫页重新解析。";
        }
        if (code.contains("BEHIND_LIVE_WINDOW")) {
            return "直播窗口已过期。";
        }
        return "播放失败，可尝试换线路或重试。";
    }

    // ══════════════ 倍速自选（参考 Kazumi 的面板式选择） ══════════════

    /** 弹出倍速面板，直接选一个值（而不是逐个循环） */
    private void showSpeedPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(this, "播放速度"));

        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(4);
        box.addView(grid);

        final Dialog d = UiKit.glassDialog(this, box);
        for (int i = 0; i < SPEEDS.length; i++) {
            final int fi = i;
            boolean cur = (i == speedIdx);
            TextView t = new TextView(this);
            t.setText(SPEED_LABELS[i]);
            t.setTextSize(13);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(dpInt(10), dpInt(10), dpInt(10), dpInt(10));
            t.setTextColor(cur ? UiKit.color(this,
                    com.google.android.material.R.attr.colorOnPrimary)
                    : UiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
            t.setBackground(UiKit.rounded(this, 10, UiKit.color(this,
                    cur ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
            t.setClickable(true);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
            lp.width = 0;
            lp.height = -2;
            lp.setMargins(dpInt(4), dpInt(4), dpInt(4), dpInt(4));
            t.setOnClickListener(v -> {
                speedIdx = fi;
                try {
                    if (player != null) player.setPlaybackSpeed(SPEEDS[fi]);
                    if (tvSpeed != null) tvSpeed.setText(SPEED_LABELS[fi]);
                } catch (Throwable ignored) {
                }
                d.dismiss();
            });
            grid.addView(t, lp);
        }
        d.show();
    }

    // ══════════════ 播放器内选集（参考 Kazumi 的选集网格） ══════════════

    /** 网格卡片式选集：当前集高亮 */
    private void showEpisodePicker() {
        if (!animeMode) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiKit.sectionHeader(this, "选集"
                + (displayTitle == null || displayTitle.isEmpty() ? "" : " · " + displayTitle)));

        ScrollView sv = new ScrollView(this);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(4);
        sv.addView(grid);
        box.addView(sv, new LinearLayout.LayoutParams(-1, dpInt(320)));

        final Dialog d = UiKit.glassDialog(this, box);
        for (int i = 0; i < epTitles.length; i++) {
            final int fi = i;
            boolean cur = (i == epIndex);
            String label = epTitles[i];
            if (label == null || label.isEmpty()) label = "第 " + (i + 1) + " 集";

            TextView t = new TextView(this);
            t.setText(label);
            t.setTextSize(12);
            t.setGravity(android.view.Gravity.CENTER);
            t.setMaxLines(2);
            t.setPadding(dpInt(6), dpInt(12), dpInt(6), dpInt(12));
            t.setTextColor(cur ? UiKit.color(this,
                    com.google.android.material.R.attr.colorOnPrimary)
                    : UiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
            t.setBackground(UiKit.rounded(this, 10, UiKit.color(this,
                    cur ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
            t.setClickable(true);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
            lp.width = 0;
            lp.height = -2;
            lp.setMargins(dpInt(4), dpInt(4), dpInt(4), dpInt(4));
            t.setOnClickListener(v -> {
                d.dismiss();
                if (fi == epIndex) return;
                switchEpisode(fi);
            });
            grid.addView(t, lp);
        }
        d.show();
    }

    /**
     * 切集：用存档的源配置重新解析目标集，拿到新直链**和新请求头**后重启本页。
     *
     * <p>为什么要重启 Activity：每集的 Referer 不同，而 ExoPlayer 的
     * OkHttpDataSource 请求头是在创建时固定的（PlayerCore.create(headers)），
     * 无法在播放中替换。重启一次最可靠，代价是短暂闪一下。</p>
     */
    private void switchEpisode(final int i) {
        if (epUrls == null || i < 0 || i >= epUrls.length) return;
        tvTitle.setText("解析中…");
        showError("正在解析第 " + (i + 1) + " 集…", null);
        errorOverlay.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                com.aliya.hyfilemgr.anime.AnimeSource src =
                        com.aliya.hyfilemgr.anime.AnimeSource.parse(sourceJson);
                if (src == null) throw new java.io.IOException("缺少源配置，无法在播放器内切集");
                com.aliya.hyfilemgr.anime.AnimeSource.Episode ep =
                        new com.aliya.hyfilemgr.anime.AnimeSource.Episode("", epTitles[i], epUrls[i]);
                com.aliya.hyfilemgr.anime.AnimeSource.PlayLink pl =
                        new com.aliya.hyfilemgr.anime.SourceEngine().resolve(src, ep, channelName);
                final com.aliya.hyfilemgr.anime.AnimeSource.PlayLink link = pl;
                runOnUiThread(() -> {
                    android.content.Intent ni = urlIntent(this, link.url, displayTitle, link.headers,
                            epUrls[i], 0L, epTitles, epUrls, i, sourceJson, channelName);
                    ni.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(ni);
                    finish();
                });
            } catch (Throwable t) {
                final String msg = t.getMessage();
                runOnUiThread(() -> {
                    hideError();
                    tvTitle.setText(baseName(playlist != null && idx < playlist.length
                            ? playlist[idx] : ""));
                    showError("切换失败：" + msg, null);
                });
            }
        }, "anime-switch-ep").start();
    }

    /** 画面比例循环切换：适应 / 拉伸 / 裁剪 */
    private void cycleRatio() {
        ratioIdx = (ratioIdx + 1) % RATIOS.length;
        try {
            if (playerView != null) playerView.setResizeMode(RATIOS[ratioIdx]);
            if (tvRatio != null) tvRatio.setText(RATIO_LABELS[ratioIdx]);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 查找同目录的外挂字幕（对齐 Kazumi 的字幕能力）。
     *
     * <p>匹配规则：与视频同名前缀的字幕文件，例如
     * {@code ep01.mp4} ↔ {@code ep01.srt} / {@code ep01.zh.ass} / {@code ep01.chs.vtt}。</p>
     * <p>只走 ExoPlayer 内建解析：SRT / SSA(ASS) / VTT。ASS 的特效渲染需要 libass，不做。</p>
     */
    private List<androidx.media3.common.MediaItem.SubtitleConfiguration> findSubtitles(
            String mediaPath, boolean isUri) {
        List<androidx.media3.common.MediaItem.SubtitleConfiguration> out = new ArrayList<>();
        if (mediaPath == null || isUri) return out;              // 网络流不找本地字幕
        try {
            File f = new File(mediaPath);
            File dir = f.getParentFile();
            if (dir == null || !dir.isDirectory()) return out;
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);

            File[] files = dir.listFiles();
            if (files == null) return out;
            for (File sub : files) {
                String n = sub.getName();
                String lower = n.toLowerCase(java.util.Locale.ROOT);
                if (!lower.startsWith(base.toLowerCase(java.util.Locale.ROOT))) continue;
                String mime = null;
                if (lower.endsWith(".srt")) mime = MimeTypes.APPLICATION_SUBRIP;
                else if (lower.endsWith(".ass") || lower.endsWith(".ssa")) mime = MimeTypes.TEXT_SSA;
                else if (lower.endsWith(".vtt")) mime = MimeTypes.TEXT_VTT;
                if (mime == null) continue;
                try {
                    out.add(new androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(
                            android.net.Uri.fromFile(sub))
                            .setMimeType(mime)
                            .setLanguage(detectLanguage(lower))
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .setLabel(n)
                            .build());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 从文件名猜语言（zh/en/ja），猜不出用 und */
    private String detectLanguage(String lowerName) {
        if (lowerName.contains("chs") || lowerName.contains("cht")
                || lowerName.contains("zh") || lowerName.contains("中文")
                || lowerName.contains("简") || lowerName.contains("繁")) return "zh";
        if (lowerName.contains("eng") || lowerName.contains("en.")) return "en";
        if (lowerName.contains("jpn") || lowerName.contains("jp")) return "ja";
        return "und";
    }

    /**
     * 截图当前画面，存到 /sdcard/Pictures/HY_VQ/。
     * 用 PixelCopy 从 Surface 取帧（API 24+，本项目 minSdk 28）；
     * 写外部存储依赖清单里的 MANAGE_EXTERNAL_STORAGE。
     */
    private void captureFrame() {
        try {
            if (playerView == null) return;
            android.view.View surfaceView = playerView.getVideoSurfaceView();
            if (!(surfaceView instanceof SurfaceView)) {
                UiKit.toast(this, "当前画面不支持截图");
                return;
            }
            final SurfaceView sv = (SurfaceView) surfaceView;
            if (sv.getWidth() <= 0 || sv.getHeight() <= 0) {
                UiKit.toast(this, "画面尚未就绪");
                return;
            }
            final Bitmap bmp = Bitmap.createBitmap(sv.getWidth(), sv.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(sv, bmp, result -> {
                if (result != PixelCopy.SUCCESS) {
                    UiKit.toast(this, "截图失败（code " + result + "）");
                    // 失败分支原本直接 return，绕过 finally 的 recycle，
                    // 每失败一次泄漏一张全屏 ARGB_8888（4K 约 33MB）
                    if (!bmp.isRecycled()) bmp.recycle();
                    return;
                }
                try {
                    File dir = new File(Environment.getExternalStorageDirectory(), "Pictures/HY_VQ");
                    if (!dir.exists() && !dir.mkdirs()) {
                        UiKit.toast(this, "无法创建截图目录");
                        if (!bmp.isRecycled()) bmp.recycle();
                        return;
                    }
                    String name = "HYVQ_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                            java.util.Locale.CHINA).format(new java.util.Date()) + ".png";
                    File out = new File(dir, name);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    UiKit.toast(this, "已保存：" + name);
                } catch (Throwable t) {
                    UiKit.toast(this, "保存失败：" + t.getMessage());
                } finally {
                    bmp.recycle();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            UiKit.toast(this, "截图异常：" + t.getMessage());
        }
    }

    private String fmt(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    @Override protected void onPause() {
        super.onPause();
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (Throwable ignored) {
        }
        updatePlayIcon(false);
        handler.removeCallbacks(ticker);
        saveHistoryProgress();                       // 离开页面即落盘
        historyHandler.removeCallbacks(historyTick);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prepared) handler.post(ticker);
        if (watchHistory != null) historyHandler.postDelayed(historyTick, 5000L);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        try {
            if (danmakuView != null) danmakuView.clear();
        } catch (Throwable ignored) {
        }
        saveHistoryProgress();
        historyHandler.removeCallbacks(historyTick);
        try {
            if (playerView != null) playerView.setPlayer(null);
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
