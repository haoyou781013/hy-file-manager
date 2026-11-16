package com.aliya.hyfilemgr.access;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录访问路由器——四类数据源的无感路由核心（对齐 MT 管理器实测行为）。
 *
 * <p><b>路由规则（真实优先，失败回退虚拟，都无则静默空）：</b></p>
 * <pre>
 *   /                      → 真实 listFiles + 白名单过滤（RootVirtualStrategy）
 *   /data                  → 虚拟：app（RootVirtualStrategy）
 *   /data/app              → PackageManager 虚拟枚举（PkgStrategy）
 *   /data/app/~~xxx        → sourceDir 路径解析虚拟补全（PkgStrategy）
 *   /data/app/~~xxx/包名    → 775 真实可读（默认真实策略）
 *   /storage               → StorageManager 卷枚举（StorageStrategy）
 *   /storage/emulated      → 虚拟：0 主用户卷（StorageStrategy）
 *   其他路径                → 真实 listFiles；失败 → 静默空列表
 * </pre>
 */
public final class AccessRouter {

    private AccessRouter() {}

    /** 列目录（主线程可调用；内部无 IO 阻塞以外的长任务） */
    public static AccessResult list(Context ctx, String path) {
        // SAF 授权 uri（content://...）优先路由：DocumentsProvider 子文档查询（app 内浏览）
        if (SafStrategy.isSafUri(path)) return SafStrategy.list(ctx, path);

        String p = normalize(path);

        // ── 特殊路径路由（虚拟层）──
        // root 已移除：系统路径一律走虚拟白名单层 + App 自身权限。
        if (p.equals("/")) return RootVirtualStrategy.listRoot();
        if (p.equals("/data")) return RootVirtualStrategy.listData();
        if (p.equals(PkgStrategy.APP_DIR)) return PkgStrategy.listAppDir(ctx);
        if (PkgStrategy.isPackageTop(p)) return PkgStrategy.listPackageTop(ctx, p);
        if (p.equals(StorageStrategy.STORAGE_DIR)) return StorageStrategy.listVolumes(ctx);
        if (p.equals(StorageStrategy.STORAGE_DIR + "/emulated")) return StorageStrategy.listEmulated(ctx);

        // ── 默认真实策略：listFiles 成功 → 真实条目（含符号链接标记） ──
        //
        // Android 11+ 的 Android/data、obb、media 被 FUSE 按**路径字符串**拦截，
        // 即使有 MANAGE_EXTERNAL_STORAGE 也 listFiles()==null。
        // 这里先按原路径试，失败再换零宽别名路径重试（见 PathAlias 的说明）。
        // 条目里的 path 一律用真实路径，零宽字符不泄漏到 UI。
        File[] real = new File(p).listFiles();
        if (real != null) {
            List<FileEntry> out = new ArrayList<>(real.length);
            for (File c : real) {
                out.add(toEntry(c));
            }
            return AccessResult.ok(out, "Real");
        }
        if (PathAlias.isGuarded(p)) {
            String aliased = PathAlias.io(p);
            if (!aliased.equals(p)) {
                File[] viaAlias = new File(aliased).listFiles();
                if (viaAlias != null) {
                    List<FileEntry> out = new ArrayList<>(viaAlias.length);
                    for (File c : viaAlias) {
                        // c.getAbsolutePath() 含零宽字符，必须先还原
                        out.add(toEntryAt(c, PathAlias.real(p)));
                    }
                    return AccessResult.ok(out, "Real");
                }
            }
        }
        // 无权限且无虚拟信息 → 静默空（对齐 MT 空列表行为，不报错）
        return AccessResult.denied("Real");
    }

    /**
     * 别名目录里的 File → FileEntry，path 用**真实父路径**拼回来。
     *
     * <p>{@code c.getAbsolutePath()} 会带上零宽字符。直接用会让
     * UI 显示错位、复制粘贴失效、比较异常。这里改成
     * 「真实父路径 + 文件名」重新拼，从源头杜绝泄漏。</p>
     */
    private static FileEntry toEntryAt(File c, String realParent) {
        String realPath = realParent.endsWith("/")
                ? realParent + c.getName()
                : realParent + "/" + c.getName();
        if (c.isDirectory()) return FileEntry.dir(realPath, c.lastModified());
        return FileEntry.file(realPath, c.length(), c.lastModified());
    }

    /**
     * 由 FileEntry 取可用于 IO 的 File。
     *
     * <p><b>为什么必须走这个入口</b>：{@code FileEntry.path} 一律是**真实路径**
     * （零宽字符绝不写入其中，否则 UI 会错位、复制粘贴失效）。
     * 但受限目录（Android/data 等）的真实路径会被 FUSE 拦住，
     * 直接 {@code new File(e.path).isFile()} 恒为 false —— 表现就是
     * 「目录能进、点文件说文件不存在」。</p>
     *
     * <p>这里统一过一次 {@link PathAlias#io}：受限路径换成别名，
     * 非受限路径原样返回。所有拿 FileEntry 做 IO 的地方都必须用它，
     * 不要再写 {@code new File(e.path)}。</p>
     */
    public static File fileOf(FileEntry e) {
        if (e == null || e.path == null) return null;
        String p = e.path;
        if (!PathAlias.isGuarded(p)) return new File(p);
        return new File(PathAlias.io(p));
    }

    /** 由路径串取可用于 IO 的 File（受限目录自动换别名） */
    public static File fileOf(String path) {
        if (path == null) return null;
        if (!PathAlias.isGuarded(path)) return new File(path);
        return new File(PathAlias.io(path));
    }

    /**
     * File → FileEntry（符号链接标记 LINK 并保留目标）。
     *
     * <p><b>为什么用 {@link android.system.Os#lstat} 而不用 {@code java.nio.file.Files}：</b></p>
     *
     * <p>① <b>版本兼容</b>：{@code Files.isSymbolicLink} 需要 <b>API 26</b>，而本项目
     * minSdk = 23。旧实现在 Android 6.x/7.x 上进入任意目录都会
     * {@code NoClassDefFoundError} 崩溃。{@code Os.lstat} 从 API 21 就有。
     * （同项目 {@code FileManagerModule.isSymlink} 用的就是版本判断兜底，
     * 这里改为彻底不依赖 Files，两边行为就一致了。）</p>
     *
     * <p>② <b>syscall 合并</b>：旧实现每个条目要 4 次系统调用
     * （{@code isSymbolicLink} + {@code isDirectory} + {@code length} + {@code lastModified}），
     * 而 {@code f.toPath()} 还会额外 new 一个 {@code UnixPath} 对象。
     * 万项目录 = 4 万次 syscall + 1 万个临时对象，全在主线程。
     * 改用 {@code lstat} 一次拿到 {@code st_mode/st_size/st_mtime}，四项判断一次完成。</p>
     *
     * <p>注：{@code st_mtime} 是秒级，与 {@code File.lastModified()}
     * 在 Android 上的实现（同样源于 {@code st_mtime}）一致，排序行为不变。</p>
     */
    public static FileEntry toEntry(File f) {
        final String path = f.getAbsolutePath();
        try {
            android.system.StructStat st = android.system.Os.lstat(path);
            final int fmt = st.st_mode & android.system.OsConstants.S_IFMT;
            final long mtime = st.st_mtime * 1000L;   // 秒 → 毫秒
            if (fmt == android.system.OsConstants.S_IFLNK) {
                return FileEntry.link(path, readLinkTarget(path), mtime);
            }
            if (fmt == android.system.OsConstants.S_IFDIR) {
                return FileEntry.dir(path, mtime);
            }
            return FileEntry.file(path, st.st_size, mtime);
        } catch (Throwable t) {
            // lstat 失败（权限不足 / SELinux 拦截 / 路径已消失）→ 退回 File API。
            // 退回路径不含 java.nio.file 调用，所以低版本也不会崩。
            return toEntryFallback(f);
        }
    }

    /**
     * 读取符号链接指向的目标（用于界面显示「-> 目标」）。
     *
     * <p>API 26+ 用 {@code Files.readSymbolicLink}（能正确处理相对路径）；
     * 低版本退回 {@code canonicalPath} 差值推算，与
     * {@code FileManagerModule.isSymlink} 的低版本兜底思路一致。</p>
     *
     * @return 链接目标；读不到返回 null（界面只显示为 LINK 类型，不显示箭头）
     */
    private static String readLinkTarget(String path) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                return java.nio.file.Files.readSymbolicLink(
                        java.nio.file.Paths.get(path)).toString();
            }
            File f = new File(path);
            File canon = f.getCanonicalFile();
            return canon.getAbsolutePath().equals(path) ? null : canon.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@link #toEntry} 的兜底实现：只用 {@code java.io.File}，零 {@code java.nio.file} 依赖。
     *
     * <p>目录/文件/链接的区分靠 {@code canonicalPath != absolutePath} 近似判断
     * （无法区分「本条目是链接」与「路径中某段是链接」，但方向安全：
     * 最多把普通条目显示成 LINK，不会漏删数据）。</p>
     */
    private static FileEntry toEntryFallback(File f) {
        final String path = f.getAbsolutePath();
        final long mtime = f.lastModified();
        boolean link = false;
        try {
            link = !f.getCanonicalPath().equals(path);
        } catch (Throwable ignored) {
        }
        if (link) {
            return FileEntry.link(path, readLinkTarget(path), mtime);
        }
        if (f.isDirectory()) {
            return FileEntry.dir(path, mtime);
        }
        return FileEntry.file(path, f.length(), mtime);
    }

    /** 规范化路径：去尾部斜杠（根除外） */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}