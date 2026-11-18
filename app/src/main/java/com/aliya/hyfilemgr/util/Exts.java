package com.aliya.hyfilemgr.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 扩展名知识库：全项目**唯一**的类型判定来源。
 *
 * <p><b>为什么要有这个类</b>：改之前同一批扩展名在三个文件里各写一份，
 * 内容逐字相同却毫无关联——</p>
 *
 * <pre>
 *   FileListAdapter.IMG_EXTS     ← 列表图标配色
 *   FileManagerModule.IMAGE_EXTS ← 决定能否用内置图片查看器
 *   （只有一份，但和上面那份内容完全一样）
 *
 *   FileListAdapter.VIDEO_EXTS     ← 列表图标
 *   FileManagerModule.VIDEO_ONLY_EXTS ← 播放器左右切换筛选
 *   MediaPlayerActivity.VIDEO_EXTS    ← 视频判定
 * </pre>
 *
 * <p>结果是：新增一个格式（比如 {@code avif} 图片、{@code opus} 音频）时
 * 必须记得改三处，漏一处就出现「详情页说是图片，列表却不显示缩略图」
 * 这种诡异不一致。集中到此处后，新增格式只改一行。</p>
 *
 * <p><b>用法</b>：{@code Exts.isImage(extOf(name))}。
 * 传入的扩展名必须已小写且不含点（见 {@link #extOf(String)} 的帮助方法）。</p>
 */
public final class Exts {

    private Exts() {}

    /** 图片：可交给内置图片查看器与缩略图解码器 */
    public static final Set<String> IMAGE = unmodifiableSet(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg",
            "ico", "tif", "tiff", "raw", "cr2", "nef", "arw", "dng", "psd", "ai");

    /**
     * 视频：可交给内置播放器。
     *
     * <p>包含两种**流媒体播放列表**：</p>
     * <ul>
     *   <li>{@code m3u8}——HLS。本地下载的片源通常是
     *       {@code playlist.m3u8 + seg_00000.ts ...} 一整个目录；
     *       播放器解析清单后按序拉本地分片，能连续播完整部片子。</li>
     *   <li>{@code mpd}——MPEG-DASH。</li>
     * </ul>
     *
     * <p>这两种不是「视频文件」而是「播放清单」，但用户视角就是视频，
     * 所以归到视频类里（点开直接进播放器，不弹「打开方式」）。</p>
     */
    public static final Set<String> VIDEO = unmodifiableSet(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf",
            "m3u8", "mpd");

    /** 音频：用弹窗播放器（原生 MediaPlayer） */
    public static final Set<String> AUDIO = unmodifiableSet(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "amr",
            "aiff", "ape", "wv", "mid", "midi", "ac3", "dts", "mka", "ra", "au", "caf");

    /** 文档：仅用于列表图标配色，不决定打开方式 */
    public static final Set<String> DOC = unmodifiableSet(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub",
            "csv", "rtf", "odt", "ods", "odp", "pages", "numbers", "key",
            "html", "htm", "xml", "json", "log", "chm", "mobi", "azw3", "tex");

    /** 压缩包：用于列表图标，实际可解压格式以 {@code ArchiveExtractor.canExtract} 为准 */
    public static final Set<String> ARCHIVE = unmodifiableSet(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "jar");

    /** 音视频合集（命中即用内置播放器，不走系统外部应用） */
    public static final Set<String> MEDIA = unmodifiableSet(
            // 视频（含 HLS/DASH 播放清单）
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf",
            "m3u8", "mpd",
            // 音频
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "amr",
            "aiff", "ape", "wv", "mid", "midi", "ac3", "dts", "mka", "ra", "au", "caf");

    // ── 判定帮助方法（传入的 ext 必须已小写、无点）──

    public static boolean isImage(String ext) {
        return ext != null && IMAGE.contains(ext);
    }

    public static boolean isVideo(String ext) {
        return ext != null && VIDEO.contains(ext);
    }

    public static boolean isAudio(String ext) {
        return ext != null && AUDIO.contains(ext);
    }

    public static boolean isMedia(String ext) {
        return ext != null && MEDIA.contains(ext);
    }

    public static boolean isDoc(String ext) {
        return ext != null && DOC.contains(ext);
    }

    public static boolean isArchive(String ext) {
        return ext != null && ARCHIVE.contains(ext);
    }

    /**
     * 取文件名的小写扩展名（不含点）；无扩展名返回空串。
     *
     * <p>本方法**每次调用都会分配**（{@code substring} + {@code toLowerCase}），
     * 在列表绑定热路径上应只算一次然后复用。注意 {@code .tar.gz} 这类复合扩展名
     * 这里只返回 {@code gz}——复合扩展名的判定请用
     * {@code FileManagerModule.ARCHIVE_NAME_SUFFIXES}。</p>
     */
    public static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ── 内部 ──

    private static Set<String> unmodifiableSet(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }
}
