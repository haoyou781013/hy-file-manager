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

    /** File → FileEntry（符号链接标记 LINK 并保留目标） */
    public static FileEntry toEntry(File f) {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
            String target = null;
            try {
                target = java.nio.file.Files.readSymbolicLink(f.toPath()).toString();
            } catch (Throwable ignored) {
            }
            return FileEntry.link(f.getAbsolutePath(), target, f.lastModified());
        }
        if (f.isDirectory()) {
            return FileEntry.dir(f.getAbsolutePath(), f.lastModified());
        }
        return FileEntry.file(f.getAbsolutePath(), f.length(), f.lastModified());
    }

    /** 规范化路径：去尾部斜杠（根除外） */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}