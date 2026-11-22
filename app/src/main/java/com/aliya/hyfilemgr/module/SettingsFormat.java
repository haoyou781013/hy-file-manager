package com.aliya.hyfilemgr.module;

/**
 * 设置页里用到的纯格式化函数。
 *
 * <p><b>为什么单独成类</b>：这些方法原本是 {@code FileManagerModule}
 * （17776 行 / 455 个方法）里的 private static 函数。它们不依赖任何实例字段
 * （无 {@code ctx} / {@code dp()} / {@code fmPrefs()}），是模块里耦合最低的一块。
 * 抽出来既减轻了 God Class 的体量，也让这些纯函数变得可单测
 * —— 之前在模块内部，想测一个 {@code fmtSeconds} 都得起整个模块。</p>
 *
 * <p><b>命名约定</b>：方法名保持与原模块内一致（{@code fmtXxx}），
 * 调用点只需加类名前缀，不需要改名，降低重构风险。</p>
 */
public final class SettingsFormat {

    private SettingsFormat() {}

    /**
     * 毫秒 → 人类可读时长。
     *
     * <p>例：3000 → "3 秒"；1500 → "1.5 秒"；120000 → "2 分钟"。</p>
     */
    public static String fmtSeconds(int ms) {
        if (ms >= 60_000 && ms % 60_000 == 0) return (ms / 60_000) + " 分钟";
        if (ms % 1000 == 0) return (ms / 1000) + " 秒";
        // Locale.US：小数点是「.」而不是某些地区的「,」
        return String.format(java.util.Locale.US, "%.1f 秒", ms / 1000f);
    }

    /** 倍速 → "2×" / "1.25×"（整数不显示小数点） */
    public static String fmtSpeed(float sp) {
        if (sp == Math.round(sp)) return ((int) sp) + "×";
        return sp + "×";
    }

    /**
     * 在档位数组里取「下一个」，到末尾回到第一个。
     *
     * @param options 候选值
     * @param cur     当前值；不在数组里时返回第一个
     */
    public static int nextIntOption(int[] options, int cur) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == cur) return options[(i + 1) % options.length];
        }
        return options[0];
    }

    /** {@link #nextIntOption} 的 float 版本（用 compare 避免浮点相等陷阱） */
    public static float nextFloatOption(float[] options, float cur) {
        for (int i = 0; i < options.length; i++) {
            if (Float.compare(options[i], cur) == 0) return options[(i + 1) % options.length];
        }
        return options[0];
    }

    /** 发布日期为空时显示「未知」 */
    public static String fmtPublishDate(String date) {
        return (date == null || date.isEmpty()) ? "未知" : date;
    }

    /**
     * 下载进度文案。
     *
     * <p>例：<br>
     * {@code "3.2 MB / 10.0 MB（32%）"}<br>
     * {@code "512 KB/s · 剩余 12 秒"}</p>
     *
     * <p>服务器没给 Content-Length 时只显示已下载量并说明速度未知
     * —— 直接显示「50%」会误导用户以为卡住。</p>
     */
    public static String formatDownloadProgress(long got, long total, long elapsedMs) {
        String now = com.aliya.hyfilemgr.filemgr.FileOps.formatSize(got);
        if (total <= 0) {
            return now + " · 速度未知（服务器未给总大小）";
        }
        String all = com.aliya.hyfilemgr.filemgr.FileOps.formatSize(total);
        long speed = elapsedMs > 0 ? got * 1000L / elapsedMs : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(now).append(" / ").append(all)
                .append("（").append(got * 100 / total).append("%）");
        if (speed > 0) {
            sb.append("\n").append(com.aliya.hyfilemgr.filemgr.FileOps.formatSize(speed)).append("/s");
            long remain = total - got;
            long secs = remain * 1000L / speed;
            if (secs > 0 && secs < 3600) {
                sb.append(" · 剩余 ").append(secs >= 60
                        ? (secs / 60) + " 分 " + (secs % 60) + " 秒"
                        : secs + " 秒");
            }
        }
        return sb.toString();
    }

    /**
     * 极简 Markdown → 纯文本（更新日志展示用）。
     *
     * <p>只处理最常见的几种标记：标题井号、列表符号、加粗/斜体/行内代码、
     * 水平分割线。不是通用解析器——更新日志是受控内容，不做复杂语法。</p>
     */
    public static String stripMarkdown(String md) {
        if (md == null || md.isEmpty()) return "";
        StringBuilder out = new StringBuilder(md.length());
        for (String raw : md.split("\n", -1)) {
            String line = raw;
            if (line.trim().matches("^-{3,}$")) {
                out.append('\n');
                continue;
            }
            int hash = 0;
            while (hash < line.length() && line.charAt(hash) == '#') hash++;
            if (hash > 0 && hash < line.length() && line.charAt(hash) == ' ') {
                out.append('\n').append(line.substring(hash + 1).trim()).append('\n');
                continue;
            }
            String t = line.trim();
            if (t.startsWith("- ") || t.startsWith("* ")) {
                line = "·  " + t.substring(2);
            }
            line = line.replace("**", "").replace("__", "").replace("`", "");
            out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /**
     * 存储项 key → 中文名（存储管理页的图例用）。
     */
    public static String displayNameOf(String key) {
        switch (key) {
            case "thumbs": return "缩略图缓存";
            case "zip": return "ZIP 浏览缓存";
            case "update": return "更新包缓存";
            case "draft": return "编辑器草稿";
            case "bg": return "背景图（旧图）";
            case "trash": return "回收站";
            default: return key;
        }
    }
}
