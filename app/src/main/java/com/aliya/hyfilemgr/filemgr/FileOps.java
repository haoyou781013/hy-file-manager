package com.aliya.hyfilemgr.filemgr;

import com.aliya.hyfilemgr.access.PathAlias;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;

/**
 * 文件管理模块：文件操作工具 + 后台执行器。
 *
 * <p>复制/移动/删除是 IO 密集操作，必须在工作线程执行（否则 ANR）；
 * 结果通过 {@link Callback} 回到主线程。删除/复制目录时递归处理，
 * 移动同分区走 rename（原子、零拷贝），跨分区退化为复制+删除。</p>
 */
public final class FileOps {

    /** 操作结果回调（主线程） */
    public interface Callback {
        void onDone(boolean success, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FileOps() {}

    // ── 格式化（委托给 AccessUtil，避免两份实现漂移）──

    /**
     * 人类可读文件大小（B/KB/MB/GB）。
     *
     * <p>实现已统一到 {@link com.aliya.hyfilemgr.access.AccessUtil#formatSize}。
     * 旧情况：FileOps 与 AccessUtil 各有一份**逐字相同**的拷贝，
     * 改精度/单位时漏改一处就会出现「同一个界面两种口径」。
     * 保留本方法作为转发入口，调用方无需改动。</p>
     */
    public static String formatSize(long bytes) {
        return com.aliya.hyfilemgr.access.AccessUtil.formatSize(bytes);
    }

    /**
     * 格式化修改时间（今年 MM-dd HH:mm，跨年 yyyy-MM-dd）。
     *
     * <p>实现已统一到 {@link com.aliya.hyfilemgr.access.AccessUtil#formatDate}。
     * 那份做了 ThreadLocal SimpleDateFormat 缓存与年份缓存，本处旧拷贝
     * 每次调用都新建 Calendar×2 + SimpleDateFormat，在列表绑定热路径上很贵。</p>
     */
    public static String formatDate(long millis) {
        return com.aliya.hyfilemgr.access.AccessUtil.formatDate(millis);
    }

    /** 目录总大小（递归求和，文件/目录通用；失败返回 -1） */
    public static long dirSize(File f) {
        try {
            if (f == null || !f.exists()) return 0;
            if (f.isFile()) return f.length();
            long total = 0;
            File[] list = f.listFiles();
            if (list == null) return 0;
            for (File c : list) {
                if (c.isDirectory()) {
                    total += dirSize(c);
                } else {
                    total += c.length();
                }
            }
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 存储空间信息："已用/总量" */
    public static String storageInfo(File dir) {
        try {
            android.os.StatFs sf = new android.os.StatFs(dir.getAbsolutePath());
            long total = sf.getTotalBytes();
            long free = sf.getAvailableBytes();
            return formatSize(total - free) + " / " + formatSize(total);
        } catch (Throwable t) {
            return "-";
        }
    }

    // ── 后台操作 ──

    /** 后台复制（文件或目录递归） */
    public static void copyAsync(File src, File destDir, Callback cb) {
        runAsync(() -> {
            try {
                copyRecursive(src, new File(destDir, src.getName()));
                return new Object[]{true, "已复制：" + src.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "复制失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 同步复制（文件或目录递归；已在后台线程时使用，失败抛 IOException） */
    public static void copy(File src, File dest) throws IOException {
        copyRecursive(alias(src), alias(dest));
    }

    /**
     * 进入受限目录时换成零宽别名路径。
     *
     * <p>Android 11+ 的 Android/data、obb、media 被 FUSE 按路径字符串拦截，
     * 直接 delete/rename 会失败；换成别名路径后底层 inode 权限是通的。
     * 见 {@link PathAlias} 的完整说明。</p>
     *
     * <p>非受限路径原样返回，零开销。</p>
     */
    private static File alias(File f) {
        if (f == null) return null;
        String p = f.getAbsolutePath();
        if (!PathAlias.isGuarded(p)) return f;
        String a = PathAlias.io(p);
        return a.equals(p) ? f : new File(a);
    }

    /** 后台移动：同分区 rename 原子完成，跨分区复制+删除 */
    public static void moveAsync(File src, File destDir, Callback cb) {
        runAsync(() -> {
            try {
                final File s2 = alias(src);
                File dest = new File(alias(destDir), src.getName());
                // 目标已存在时不能直接 renameTo：部分文件系统会静默覆盖，
                // 用户以为"移动"实则丢了原文件。交给上层重命名去。
                if (dest.exists()) {
                    return new Object[]{false, "目标已存在同名项：" + src.getName()};
                }
                if (s2.renameTo(dest)) {
                    return new Object[]{true, "已移动：" + src.getName()};
                }
                // 跨分区：copyRecursive 现在保证「不完整就抛异常」
                // （listFiles()==null 已改为抛 IOException），
                // 因此能走到下一行就说明复制完整，删源是安全的。
                copyRecursive(s2, dest);
                deleteRecursive(s2);
                return new Object[]{true, "已移动（跨分区）：" + src.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "移动失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台删除（文件或目录递归），返回删除的条目数 */
    public static void deleteAsync(File src, Callback cb) {
        runAsync(() -> {
            try {
                int n = deleteRecursive(alias(src));
                return new Object[]{true, "已删除 " + n + " 项"};
            } catch (Throwable t) {
                return new Object[]{false, "删除失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台新建文件夹 */
    public static void mkdirAsync(File parent, String name, Callback cb) {
        runAsync(() -> {
            try {
                File dir = new File(alias(parent), sanitize(name));
                if (dir.exists()) return new Object[]{false, "已存在同名文件/文件夹"};
                if (!dir.mkdirs()) return new Object[]{false, "创建失败（无写入权限？）"};
                return new Object[]{true, "已创建文件夹：" + dir.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "创建失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台新建文件（支持带后缀/无后缀文件名） */
    public static void createFileAsync(File parent, String name, Callback cb) {
        runAsync(() -> {
            try {
                String n = sanitize(name);
                if (n.isEmpty()) return new Object[]{false, "文件名不能为空"};
                File f = new File(alias(parent), n);
                if (f.exists()) return new Object[]{false, "已存在同名文件/文件夹"};
                if (!f.createNewFile()) return new Object[]{false, "创建失败（无写入权限？）"};
                return new Object[]{true, "已创建文件：" + f.getName()};
            } catch (Throwable t) {
                return new Object[]{false, "创建失败：" + t.getMessage()};
            }
        }, cb);
    }

    /** 后台重命名 */
    public static void renameAsync(File src, String newName, Callback cb) {
        runAsync(() -> {
            try {
                String n = sanitize(newName);
                if (n.isEmpty()) return new Object[]{false, "名称不能为空"};
                final File s2 = alias(src);
                File dest = new File(alias(src.getParentFile()), n);
                if (dest.exists()) return new Object[]{false, "已存在同名文件/文件夹"};
                if (!s2.renameTo(dest)) return new Object[]{false, "重命名失败"};
                return new Object[]{true, "已重命名为：" + n};
            } catch (Throwable t) {
                return new Object[]{false, "重命名失败：" + t.getMessage()};
            }
        }, cb);
    }

    // ── 内部 ──

    private static void runAsync(final java.util.function.Supplier<Object[]> task, final Callback cb) {
        new Thread(() -> {
            Object[] result = task.get();
            MAIN.post(() -> {
                if (cb != null) cb.onDone((Boolean) result[0], (String) result[1]);
            });
        }, "HyVqFileOps").start();
    }

    /** 递归复制（含目录结构与文件），目标已存在时覆盖 */
    private static void copyRecursive(File src, File dest) throws IOException {
        // 防自嵌套：把 /a 复制到 /a/b 会无限递归直到栈溢出/写满磁盘
        try {
            String ss = src.getCanonicalPath();
            String dd = dest.getCanonicalPath();
            if (dd.equals(ss) || dd.startsWith(ss + java.io.File.separator)) {
                throw new java.io.IOException("不能把目录复制到它自身或其子目录内");
            }
        } catch (java.io.IOException e) {
            throw e;
        } catch (Throwable ignored) {
        }
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("无法创建目录 " + dest);
            File[] children = src.listFiles();
            if (children == null) {
                // ⚠️ 必须抛异常，不能静默跳过。
                //
                // listFiles() 返回 null 只有两种可能：不是目录（上面已判过），
                // 或**没有读取权限**（典型如 /Android/data/<别的应用>）。
                // 旧实现 if (children != null) 直接跳过 → 本次 copy「正常返回」，
                // 而调用方（剪切/移动/移入回收站）在 copy 之后无条件删源，
                // 结果是被跳过的子目录内容**永久丢失**，且对外报成功。
                throw new IOException("无法读取目录内容（可能无权限）：" + src);
            }
            for (File c : children) {
                copyRecursive(c, new File(dest, c.getName()));
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录 " + parent);
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /** 递归删除，返回删除条目数（文件+目录） */
    /**
     * 递归删除。
     *
     * @return 实际删除成功的条目数（失败的不计入，与旧实现「无论成败都 +1」不同）
     *
     * <p>旧实现忽略 delete() 返回值与 listFiles() 为 null 的情况，
     * 只读子项 / SELinux 拒绝时会谎报「已删除 N 项」。</p>
     */
    /**
     * 是否是符号链接本身（不跟随），零 {@code java.nio.file} 依赖。
     *
     * <p>低版本用 {@code canonicalPath != absolutePath} 兜底：不完全等价
     * （路径中任意一段是链接都会判为 true），但方向安全（少删不多删）。</p>
     *
     * <p>与 {@code FileManagerModule.isSymlink}、{@code ArchiveExtractor.isSymlinkStatic}
     * 三处同构——本来应该抽到公共工具类，但那个类还没建，先保证行为一致。</p>
     */
    private static boolean isSymlink(File f) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                return java.nio.file.Files.isSymbolicLink(
                        java.nio.file.Paths.get(f.getAbsolutePath()));
            }
            File p = f.getParentFile();
            File canon = p == null ? f : new File(p.getCanonicalFile(), f.getName());
            return !canon.getCanonicalPath().equals(canon.getAbsolutePath());
        } catch (Throwable t) {
            return false;
        }
    }

    private static int deleteRecursive(File f) {
        if (f == null || !f.exists()) return 0;
        int count = 0;
        // ⚠ symlink 不能递归：`f.isDirectory()` 对「指向目录的符号链接」返回 true，
        // 旧实现直接 listFiles() 递归 → 进入链接目标目录、把目标内容真删了。
        // 对照：FileManagerModule.deleteTree(16954) 与
        // ArchiveExtractor.deleteTreeQuietly 都判了 isSymlink，唯独这里漏了。
        // delete() 对 symlink 只移除链接本身，不动目标。
        if (f.isDirectory() && !isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    count += deleteRecursive(c);
                }
            }
        }
        return f.delete() ? count + 1 : count;
    }

    /** 文件名净化：去路径分隔符/控制字符，防路径穿越 */
    private static String sanitize(String name) {
        if (name == null) return "";
        String n = name.replace('/', ' ').replace('\\', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (n.equals(".") || n.equals("..")) return "";
        return n.length() > 120 ? n.substring(0, 120) : n;
    }
}