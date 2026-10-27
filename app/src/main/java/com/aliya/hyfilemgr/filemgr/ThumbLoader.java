package com.aliya.hyfilemgr.filemgr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件列表缩略图加载器。
 *
 * <p>支持三类：
 * <ul>
 *   <li>图片：BitmapFactory 采样解码（按目标尺寸 inSampleSize，避免 OOM）</li>
 *   <li>视频：MediaMetadataRetriever 取首帧</li>
 *   <li>APK：PackageManager 取应用图标（未安装也能从包内读）</li>
 * </ul>
 *
 * <p>三级缓存：LruCache 内存 → 磁盘（cacheDir/thumbs）→ 源文件。
 * 加载在固定线程池内进行，完成后回主线程 setImageBitmap。
 * 通过 ImageView 的 tag 校验防止复用错位（RecyclerView 滚动快速时必需）。</p>
 */
public final class ThumbLoader {

    /** 缩略图目标边长（px 基准，实际按 density 缩放由调用方决定） */
    private static final int TARGET_PX = 96;
    /** 磁盘缓存上限 200MB（按文件数粗控，每个约 5~20KB） */
    private static final int DISK_MAX_FILES = 4000;

    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b == null ? 0 : b.getByteCount();
                }
            };

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "thumb-" + COUNTER.incrementAndGet());
                t.setPriority(Thread.MIN_PRIORITY);   // 不抢 UI 线程
                t.setDaemon(true);
                return t;
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThumbLoader() { }

    /** 该条目是否有缩略图（按扩展名快速判定） */
    public static boolean supports(String name) {
        if (name == null) return false;
        String low = name.toLowerCase(Locale.ROOT);
        return isImage(low) || isVideo(low) || low.endsWith(".apk");
    }

    public static boolean isImage(String low) {
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")
                || low.endsWith(".webp") || low.endsWith(".bmp") || low.endsWith(".gif")
                || low.endsWith(".heic") || low.endsWith(".heif") || low.endsWith(".avif");
    }

    public static boolean isVideo(String low) {
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".webm")
                || low.endsWith(".avi") || low.endsWith(".mov") || low.endsWith(".3gp")
                || low.endsWith(".flv") || low.endsWith(".ts") || low.endsWith(".m4v");
    }

    /**
     * 异步加载缩略图到 ImageView。
     *
     * @param iv  目标 ImageView（会在加载完成时校验其 tag 防错位）
     * @param key 缓存键：优先用文件绝对路径 + mtime（文件变了自动失效）
     */
    public static void load(Context ctx, final ImageView iv, final String path,
                            final String name, final long mtime) {
        if (iv == null || path == null) return;
        final String cacheKey = path + "@" + mtime;
        iv.setTag(cacheKey);

        // ① 内存
        Bitmap b = MEM.get(cacheKey);
        if (b != null) {
            iv.setColorFilter(null);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(b);
            return;
        }

        // 先清掉上一张（RecyclerView 复用时会残留）
        iv.setImageBitmap(null);
        iv.setColorFilter(null);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            Bitmap out = readDisk(app, cacheKey);
            if (out == null) {
                out = decode(app, path, name);
                if (out != null) writeDisk(app, cacheKey, out);
            }
            final Bitmap fin = out;
            if (fin == null) return;
            MAIN.post(() -> {
                // 防错位：加载完成时 ImageView 已被复用为别的条目 → 丢弃
                if (!cacheKey.equals(iv.getTag())) return;
                iv.setColorFilter(null);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(fin);
            });
        });
    }

    // ── 解码 ──

    private static Bitmap decode(Context ctx, String path, String name) {
        String low = name == null ? path.toLowerCase(Locale.ROOT)
                : name.toLowerCase(Locale.ROOT);
        try {
            if (isImage(low)) return decodeImage(path);
            if (isVideo(low)) return decodeVideo(path);
            if (low.endsWith(".apk")) return decodeApk(path);
        } catch (Throwable ignored) {
            // 解码失败静默（列表里保留类型图标即可，不弹错误打扰）
        }
        return null;
    }

    private static Bitmap decodeImage(String path) {
        // 先只读尺寸
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, TARGET_PX);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // 省一半内存，缩略图足够
        return BitmapFactory.decodeFile(path, o);
    }

    private static Bitmap decodeVideo(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            // API 27+ 有 scaled 重载；低版本取全帧再缩
            Bitmap f = null;
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                f = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        TARGET_PX, TARGET_PX);
            }
            if (f == null) {
                f = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return f == null ? null : scaleTo(f, TARGET_PX);
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap decodeApk(String path) {
        Context app = HOLDER;
        if (app == null) return null;
        android.content.pm.PackageManager pm = app.getPackageManager();
        android.content.pm.PackageInfo pi =
                pm.getPackageArchiveInfo(path, 0);
        if (pi == null || pi.applicationInfo == null) return null;
        android.content.pm.ApplicationInfo ai = pi.applicationInfo;
        // API 8+ 需要显式给 sourceDir 才能加载未安装包图标
        ai.sourceDir = path;
        ai.publicSourceDir = path;
        android.graphics.drawable.Drawable d = ai.loadIcon(pm);
        if (d == null) return null;
        int s = Math.max(d.getIntrinsicWidth(), TARGET_PX);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, s, s);
        d.draw(c);
        return scaleTo(b, TARGET_PX);
    }

    private static Bitmap scaleTo(Bitmap src, int max) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int big = Math.max(w, h);
        if (big <= max) return src;
        float f = (float) max / big;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
        if (out != src) src.recycle();
        return out;
    }

    private static int sampleSize(int w, int h, int target) {
        int s = 1;
        while (w / s > target * 2 && h / s > target * 2) s *= 2;
        return s;
    }

    // ── 磁盘缓存 ──

    private static File diskFile(Context ctx, String key) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String fn = Integer.toHexString(key.hashCode()) + ".jpg";
        return new File(dir, fn);
    }

    private static Bitmap readDisk(Context ctx, String key) {
        File f = diskFile(ctx, key);
        if (f == null || !f.isFile() || f.length() == 0) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) MEM.put(key, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeDisk(Context ctx, String key, Bitmap b) {
        File f = diskFile(ctx, key);
        if (f == null) return;
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Throwable ignored) {
        }
        MEM.put(key, b);
        trimDisk(ctx);
    }

    /** 磁盘缓存超量时按最后修改时间清掉最旧的一批 */
    private static void trimDisk(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "thumbs");
        File[] all = dir.listFiles();
        if (all == null || all.length <= DISK_MAX_FILES) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int del = all.length - DISK_MAX_FILES;
        for (int i = 0; i < del && i < all.length; i++) all[i].delete();
    }

    // APK 解码需要 Context，用静态持有（Application 级，不会泄漏）
    private static volatile Context HOLDER;

    /** 首次使用前调用一次（传 Application context） */
    public static void init(Context app) {
        HOLDER = app;
    }
}
