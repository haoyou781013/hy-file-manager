package com.aliya.hyfilemgr.access;

/**
 * 受限目录的路径别名。
 *
 * <p><b>背景</b>：Android 11+ 把 {@code /sdcard/Android/data}、{@code obb}、
 * {@code media} 移到了 FUSE 层做**路径字符串**拦截 —— 即使应用拿到了
 * {@code MANAGE_EXTERNAL_STORAGE}，{@code listFiles()} 对这些目录仍返回 null。</p>
 *
 * <p><b>但拦截的是字符串匹配，不是权限位</b>。目录本身的权限是
 * {@code drwxrws--x}（有 x 位，可进入），真正的 inode 权限从未收紧。
 * 在 {@code Android} 与 {@code data} 之间插入一个零宽空格
 * （U+200B）后，FUSE 的拦截规则匹配不上，而 Linux VFS 在解析目录项时
 * 会忽略这个字符，最终落到同一个 inode —— 于是读写删全部通行。</p>
 *
 * <p><b>这是一个未修复的系统行为，不是官方支持的能力。</b>
 * Google 在文档里明确说「拿到 MANAGE_EXTERNAL_STORAGE 仍不能访问
 * 其他应用的 app-specific 目录」。此实现依赖该字符处理缺陷，
 * 一旦系统修复即失效。所有调用点都必须能容忍「别名也失败」的情况，
 * 回退到原来的「无权限」空列表。</p>
 *
 * <p><b>对 UI 的约束</b>：零宽字符绝不能泄漏到界面上或持久化到任何地方 ——
 * 它会让路径栏错位、复制粘贴失效、字符串比较异常。
 * 对外一律通过 {@link #real(String)} 还原。</p>
 */
public final class PathAlias {

    private PathAlias() {}

    /** 零宽空格 U+200B */
    public static final String ZW = "\u200b";

    /** 受 FUSE 拦截的二级目录名 */
    private static final String[] GUARDED = {"data", "obb", "media"};

    /** 这个路径是否位于受限区（/Android/data、/Android/obb、/Android/media 之下） */
    public static boolean isGuarded(String path) {
        if (path == null) return false;
        for (String g : GUARDED) {
            if (path.contains("/Android/" + g + "/") || path.endsWith("/Android/" + g)) {
                return true;
            }
        }
        return false;
    }

    /** 路径里是否已经带了零宽字符（说明是内部别名形式） */
    public static boolean isAliased(String path) {
        return path != null && path.indexOf('\u200b') >= 0;
    }

    /**
     * 真实路径 → 别名路径。
     *
     * <p>只在受限区插入零宽字符：{@code /Android/data/x} → {@code /Android/<ZW>data/x}。
     * 非受限路径原样返回。</p>
     *
     * <p>已经带零宽的路径原样返回，保证幂等 —— 多处调用不会叠加出多个零宽。</p>
     */
    public static String io(String path) {
        if (path == null || path.isEmpty()) return path;
        if (isAliased(path)) return path;
        for (String g : GUARDED) {
            String needle = "/Android/" + g;
            int idx = path.indexOf(needle);
            if (idx >= 0) {
                return path.substring(0, idx) + "/Android/" + ZW + g
                        + path.substring(idx + needle.length());
            }
        }
        return path;
    }

    /** 别名路径 → 真实路径（去掉所有零宽空格）。用于 UI 显示与任何持久化。 */
    public static String real(String path) {
        if (path == null) return null;
        if (path.indexOf('\u200b') < 0) return path;
        return path.replace("\u200b", "");
    }
}
