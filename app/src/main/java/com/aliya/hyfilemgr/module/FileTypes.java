package com.aliya.hyfilemgr.module;

import android.graphics.BitmapFactory;
import android.webkit.MimeTypeMap;

import com.aliya.hyfilemgr.util.Exts;

/**
 * 文件类型判定与 MIME 映射。
 *
 * <p><b>为什么单独成类</b>：这些方法原本是 {@code FileManagerModule}
 * 里的 private static 函数，其中 {@code mimeOf} 一个方法就占了 100+ 行
 * 的纯查表逻辑，把整个模块的行数撑大且与模块的业务逻辑毫无关系。
 * 抽出来后模块只管交互，文件类型判断放在这里。</p>
 *
 * <p><b>为什么不并进 {@code util/Exts}</b>：{@code Exts} 只做「扩展名是否属于
 * 某类别」的判定（无 Android 依赖，可纯 JVM 测试）；本类需要
 * {@link MimeTypeMap}（Android 框架类）。两者测试成本不同，分开更清楚。</p>
 */
public final class FileTypes {

    private FileTypes() {}

    /** 扩展名 → MIME；未知时交给系统 {@link MimeTypeMap} */
    public static String mimeOf(String ext) {
        if (ext == null || ext.isEmpty()) return null;
        switch (ext) {
            case "md": return "text/markdown";
            case "epub": return "application/epub+zip";
            case "mobi": return "application/x-mobipocket-ebook";
            case "azw3": return "application/vnd.amazon.ebook";
            case "chm": return "application/vnd.ms-htmlhelp";
            case "tex": return "application/x-tex";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "psd": return "image/vnd.adobe.photoshop";
            case "ico": return "image/x-icon";
            case "mkv": return "video/x-matroska";
            case "m2ts": return "video/mp2t";
            case "mts": return "video/mp2t";
            case "ts": return "video/mp2t";
            case "m4v": return "video/x-m4v";
            case "wmv": return "video/x-ms-wmv";
            case "flv": return "video/x-flv";
            case "3gp": return "video/3gpp";
            case "ogv": return "video/ogg";
            case "flac": return "audio/flac";
            case "opus": return "audio/opus";
            case "amr": return "audio/amr";
            case "wma": return "audio/x-ms-wma";
            case "ape": return "audio/ape";
            case "aiff": return "audio/aiff";
            case "mid": return "audio/midi";
            case "midi": return "audio/midi";
            case "ac3": return "audio/ac3";
            case "7z": return "application/x-7z-compressed";
            case "rar": return "application/x-rar-compressed";
            case "tar": return "application/x-tar";
            case "gz": return "application/gzip";
            case "tgz": return "application/gzip";
            case "bz2": return "application/x-bzip2";
            case "xz": return "application/x-xz";
            case "iso": return "application/x-iso9660-image";
            case "apk": return "application/vnd.android.package-archive";
            case "jar": return "application/java-archive";
            case "xml": return "text/xml";
            case "json": return "application/json";
            case "yaml": return "text/yaml";
            case "yml": return "text/yaml";
            case "csv": return "text/csv";
            case "log": return "text/plain";
            case "sql": return "application/sql";
            case "torrent": return "application/x-bittorrent";
            case "sh": return "application/x-sh";
            case "bash": return "application/x-sh";
            case "bat": return "application/x-msdos-program";
            case "ps1": return "application/x-powershell";
            case "java": return "text/x-java-source";
            case "kt": return "text/x-kotlin";
            case "c": return "text/x-c";
            case "h": return "text/x-c";
            case "cpp": return "text/x-c++";
            case "hpp": return "text/x-c++";
            case "cs": return "text/x-csharp";
            case "py": return "text/x-python";
            case "js": return "application/javascript";
            case "mjs": return "application/javascript";
            case "jsx": return "text/jsx";
            case "tsx": return "text/tsx";
            case "vue": return "text/vue";
            case "css": return "text/css";
            case "scss": return "text/x-scss";
            case "less": return "text/x-less";
            case "php": return "text/x-php";
            case "rb": return "text/x-ruby";
            case "go": return "text/x-go";
            case "rs": return "text/x-rust";
            case "swift": return "text/x-swift";
            case "lua": return "text/x-lua";
            case "pl": return "text/x-perl";
            case "r": return "text/x-r";
            case "dart": return "text/x-dart";
            case "groovy": return "text/x-groovy";
            case "gradle": return "text/x-gradle";
            case "properties": return "text/x-java-properties";
            case "ini": return "text/plain";
            case "cfg": return "text/plain";
            case "conf": return "text/plain";
            case "ipynb": return "application/x-ipynb+json";
            case "fb2": return "application/x-fictionbook+xml";
            case "djvu": return "image/vnd.djvu";
            case "cbr": return "application/vnd.comicbook-rar";
            case "cbz": return "application/vnd.comicbook+zip";
            case "mht": return "message/rfc822";
            case "zst": return "application/zstd";
            case "lz4": return "application/x-lz4";
            case "cab": return "application/vnd.ms-cab-compressed";
            case "deb": return "application/vnd.debian.binary-package";
            case "rpm": return "application/x-rpm";
            case "dmg": return "application/x-apple-diskimage";
            case "aab": return "application/x-authorware-bin";
            case "xapk": return "application/vnd.android.package-archive";
            case "svgz": return "image/svg+xml";
            default: return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            }
    }

    /** 解码出的图片格式 → 文件扩展名（用于给缓存文件命名） */
    public static String guessExt(android.graphics.BitmapFactory.Options o) {
        String m = o.outMimeType;
        if (m == null) return ".img";
        if (m.contains("jpeg")) return ".jpg";
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";
        if (m.contains("gif")) return ".gif";
        return ".img";
    }

    /** 文件名 → 扩展名（不含点，已小写） */
    public static String extOf(String name) {
        return Exts.extOf(name);
    }

    /**
     * 是否图片文件。
     *
     * <p>统一走 {@link Exts}（19 种扩展名），不再手写 endsWith 列表 ——
     * 旧实现只cover 6 种，导致相机拍的 HEIC 在自建浏览器里看不到。</p>
     */
    public static boolean isImageName(String name) {
        // 已统一到 util/Exts（19 种图片扩展名）。
        // 旧实现手写 endsWith×6（jpg/jpeg/png/webp/bmp/gif）——
        // 背景图浏览器会把 heic/heif/svg/tiff/raw 等全滤掉，
        // 用户相机拍的 HEIC 照片在自建浏览器里根本看不到。
        return Exts.isImage(Exts.extOf(name));
    }
}
