package com.aliya.hyfilemgr.player;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 播放内核的统一出口 —— 全项目只有这里 new ExoPlayer。
 *
 * <h3>为什么要统一</h3>
 * 改造前是两套：视频走 {@code VideoView}（{@code MediaPlayer} + Surface），
 * 音频走 {@code FileManagerModule} 里的裸 {@code MediaPlayer}。
 * 代码注释自承「{@code VideoView} 依赖 Surface，把 View 隐藏后音频无输出，故音频不走本页」
 * —— 这就是没能统一的证据。
 *
 * <h3>ExoPlayer 为什么能统一</h3>
 * <ul>
 *   <li>{@code ExoPlayer} 本身**不依赖 Surface**：音频可直接出声，视频用 {@code PlayerView} 挂 Surface。</li>
 *   <li>本地文件 / {@code content://} / HTTP / HLS 用同一个 {@link MediaItem} 抽象。</li>
 *   <li><b>能注入请求头（含 HLS 分片）</b>——这是番剧源能播的前提。
 *       实测采集到的源几乎都要求特定 User-Agent / Referer / Cookie。</li>
 * </ul>
 *
 * <h3>为什么请求头要设在 DataSource 层</h3>
 * {@code MediaPlayer.setDataSource(headers)} 的请求头**只对被请求的那个 URL 生效**，
 * m3u8 里的分片请求带不上（androidx/media issue #1750 已确认）。
 * 而 {@link OkHttpDataSource.Factory#setDefaultRequestProperty} 作用在整条链路上，
 * 播放列表与分片都会带上。
 */
public final class PlayerCore {

    /** 复用同一个 OkHttp 客户端：共享连接池与线程，避免播放器各自为政 */
    private static volatile OkHttpClient sHttp;

    private PlayerCore() {
    }

    public static OkHttpClient httpClient() {
        OkHttpClient c = sHttp;
        if (c == null) {
            synchronized (PlayerCore.class) {
                c = sHttp;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                    sHttp = c;
                }
            }
        }
        return c;
    }

    /** 不带自定义请求头的播放器（本地文件场景） */
    public static ExoPlayer create(Context ctx) {
        return create(ctx, null);
    }

    /**
     * 创建播放器。
     *
     * @param headers 附加到**所有**请求上的头（Referer / User-Agent / Cookie 等）。
     *                传 null 表示不加。注意：同名头若与 OkHttp 默认冲突，以这里设置的为准。
     */
    public static ExoPlayer create(Context ctx, Map<String, String> headers) {
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(httpClient());
        if (headers != null && !headers.isEmpty()) {
            // 注意：Media3 1.1.1 只有复数的 setDefaultRequestProperties(Map)，
            // 单数版 setDefaultRequestProperty(String,String) 是高版本才加的（实测编译不过）。
            java.util.Map<String, String> props = new java.util.HashMap<>();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    props.put(e.getKey(), e.getValue());
                }
            }
            if (!props.isEmpty()) {
                http.setDefaultRequestProperties(props);
            }
        }
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(ctx.getApplicationContext(), http);

        // ── 音频焦点与耳机拔出 ──
        //
        // 此前完全没有这两项配置，三个日常场景全错：
        //   ① 来电 / 其他 App 播音乐 → 本播放器不暂停，声音叠在一起
        //   ② 拔耳机 → 继续外放，公共场合很尴尬
        //   ③ 切后台 → 不释放 codec（见 MediaPlayerActivity 的 onStop）
        //
        // media3 的 ExoPlayer.Builder 已内建 AudioFocusManager：
        //   setAudioAttributes(attrs, handleAudioFocus=true) 让它自动申请/放弃
        //   音频焦点，并在 AUDIOFOCUS_LOSS / LOSS_TRANSIENT 时按策略暂停或降音量。
        //   setHandleAudioBecomingNoisy(true) 注册 ACTION_AUDIO_BECOMING_NOISY，
        //   耳机拔出时自动暂停。
        // 两处都是 media3 1.11.1 的公开 API（已用 javap 确认），无需手写
        // AudioFocusRequest，也不用申请任何权限。
        androidx.media3.common.AudioAttributes attrs =
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build();

        return new ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
                .setAudioAttributes(attrs, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();
    }

    /**
     * 应用 Context（zip 虚拟路径解出时需要 cacheDir）。
     *
     * <p>用 Application context，不会泄漏 Activity。由 MediaPlayerActivity /
     * 音频弹窗在首次播放前调用 {@link #init}。</p>
     */
    private static volatile Context HOLDER;

    /** 首次使用前调用一次（传 Application context） */
    public static void init(Context app) {
        HOLDER = app == null ? null : app.getApplicationContext();
    }

    /**
     * 把「路径 / content:// / http(s) URL / zip:// 虚拟路径」统一成 MediaItem。
     *
     * <p><b>zip:// 必须在此处就解出</b>：ExoPlayer 需要一个可 seek 的真实文件或 URI，
     * 它不认识本项目自定义的 zip:// 协议。传进去会得到
     * {@code UnrecognizedInputFormatException} 或直接文件不存在。
     * 调用方（FileManagerModule）已保证传入的 zip:// 条目能被解出，
     * 这里解到 cacheDir/zipmedia 后走普通文件路径。</p>
     *
     * @param isUri 路径是否已经是 URI 字符串（SAF 的 content:// 或网络地址）
     */
    public static MediaItem mediaItem(String path, boolean isUri) {
        if (path == null) {
            return null;
        }
        // zip 虚拟路径：先解出到缓存再当普通文件播
        if (path.startsWith("zip://")) {
            String real = ZipMediaCache.extract(path);
            if (real == null) return null;
            path = real;
            isUri = false;
        }
        Uri uri;
        if (isUri || path.startsWith("http://") || path.startsWith("https://")
                || path.startsWith("content://") || path.startsWith("file://")) {
            uri = Uri.parse(path);
        } else {
            uri = Uri.fromFile(new File(path));
        }
        return MediaItem.fromUri(uri);
    }

    /**
     * ZIP 内媒体条目解出缓存。
     *
     * <p>与图片的 zipview / zipthumb 分开放在 zipmedia：
     * 媒体文件通常几十 MB，混在一起不利于按类型清理（设置里的存储管理按目录清）。</p>
     *
     * <p>缓存名用 SHA-256 前 16 字符 + 条目名——不用 hashCode，
     * 它只有 32 位，碰撞会导致「播到别的视频」。</p>
     */
    /** ZIP 内媒体条目解出：统一委托 ZipCache（含 per-archive 追踪与退出清理） */
    private static final class ZipMediaCache {
        static String extract(String virtPath) {
            android.content.Context ctx = HOLDER;
            if (ctx == null) return null;
            String[] parts = com.aliya.hyfilemgr.archive.ZipCache.splitVirtual(virtPath);
            if (parts == null) return null;
            return com.aliya.hyfilemgr.archive.ZipCache.extractPath(
                    ctx, com.aliya.hyfilemgr.archive.ZipCache.DIR_MEDIA,
                    parts[0], parts[1]);
        }
    }
}
