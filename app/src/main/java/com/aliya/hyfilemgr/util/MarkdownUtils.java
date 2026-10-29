package com.aliya.hyfilemgr.util;

/**
 * 轻量 Markdown → HTML 转换（零依赖，纯 Java 实现，可闭源）。
 *
 * <p>支持常用子集：标题 #~######、粗体/斜体/删除线、行内代码、
 * 代码块 ```、引用 &gt;、无序/有序列表、分割线、链接、图片、表格。
 * 足够覆盖笔记、README 的日常阅读需求。</p>
 *
 * <p>输出经过 HTML 转义，不会执行脚本；配合 WebView 关闭 JS 使用。</p>
 */
public final class MarkdownUtils {

    private MarkdownUtils() { }

    /** Markdown 文本 → HTML body 片段（不含 html/head） */
    public static String toHtml(String md) {
        if (md == null || md.isEmpty()) return "";
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(md.length() * 2);
        boolean inCode = false;
        boolean inUl = false;
        boolean inOl = false;
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw;

            // ── 代码块 ──
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    out.append("</code></pre>\n");
                    inCode = false;
                } else {
                    closeLists(out, inUl, inOl); inUl = inOl = false;
                    String lang = trimmed.substring(3).trim();
                    out.append("<pre><code")
                       .append(lang.isEmpty() ? "" : " class=\"lang-" + esc(lang) + "\"")
                       .append(">");
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                out.append(esc(line)).append('\n');
                continue;
            }

            // ── 表格 ──
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|", -1);
                boolean isSep = true;
                for (String c : cells) {
                    if (!c.trim().matches(":?-{2,}:?")) { isSep = false; break; }
                }
                if (isSep) continue;   // 跳过分隔行
                if (!inTable) { out.append("<table>\n"); inTable = true; }
                out.append("<tr>");
                for (String c : cells) out.append("<td>").append(inline(c.trim())).append("</td>");
                out.append("</tr>\n");
                continue;
            } else if (inTable) {
                out.append("</table>\n");
                inTable = false;
            }

            // ── 空行 ──
            if (trimmed.isEmpty()) {
                closeLists(out, inUl, inOl); inUl = inOl = false;
                continue;
            }

            // ── 分割线 ──
            if (trimmed.matches("-{3,}|\\*{3,}|_{3,}")) {
                closeLists(out, inUl, inOl); inUl = inOl = false;
                out.append("<hr/>\n");
                continue;
            }

            // ── 标题 ──
            int hash = 0;
            while (hash < trimmed.length() && trimmed.charAt(hash) == '#') hash++;
            if (hash > 0 && hash <= 6 && hash < trimmed.length()
                    && trimmed.charAt(hash) == ' ') {
                closeLists(out, inUl, inOl); inUl = inOl = false;
                String text = trimmed.substring(hash + 1).trim();
                out.append("<h").append(hash).append('>')
                   .append(inline(text))
                   .append("</h").append(hash).append(">\n");
                continue;
            }

            // ── 引用 ──
            if (trimmed.startsWith("&gt;") || trimmed.startsWith(">")) {
                closeLists(out, inUl, inOl); inUl = inOl = false;
                String text = trimmed.startsWith("&gt;")
                        ? trimmed.substring(4) : trimmed.substring(1);
                out.append("<blockquote>").append(inline(text.trim()))
                   .append("</blockquote>\n");
                continue;
            }

            // ── 有序列表 ──
            java.util.regex.Matcher mo =
                    java.util.regex.Pattern.compile("^(\\d+)\\.\\s+(.*)$").matcher(trimmed);
            if (mo.matches()) {
                if (!inOl) { closeLists(out, inUl, inOl); inUl = false;
                    out.append("<ol>\n"); inOl = true; }
                out.append("<li>").append(inline(mo.group(2))).append("</li>\n");
                continue;
            }

            // ── 无序列表 ──
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")
                    || trimmed.startsWith("+ ")) {
                if (!inUl) { closeLists(out, inUl, inOl); inOl = false;
                    out.append("<ul>\n"); inUl = true; }
                out.append("<li>").append(inline(trimmed.substring(2))).append("</li>\n");
                continue;
            }

            // ── 普通段落 ──
            closeLists(out, inUl, inOl); inUl = inOl = false;
            out.append("<p>").append(inline(trimmed)).append("</p>\n");
        }

        if (inCode) out.append("</code></pre>\n");
        if (inTable) out.append("</table>\n");
        closeLists(out, inUl, inOl);
        return out.toString();
    }

    private static void closeLists(StringBuilder out, boolean ul, boolean ol) {
        if (ul) out.append("</ul>\n");
        if (ol) out.append("</ol>\n");
    }

    /** 行内标记：转义 → 行内代码 → 粗体 → 斜体 → 删除线 → 链接/图片 */
    private static String inline(String s) {
        String t = esc(s);

        // 行内代码（先占位，避免内部被其它规则处理）
        java.util.List<String> codes = new java.util.ArrayList<>();
        java.util.regex.Matcher mc =
                java.util.regex.Pattern.compile("`([^`]+)`").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (mc.find()) {
            codes.add(mc.group(1));
            mc.appendReplacement(sb, "\u0000CODE" + (codes.size() - 1) + "\u0000");
        }
        mc.appendTail(sb);
        t = sb.toString();

        // 图片 ![alt](url)
        t = t.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)",
                "<img alt=\"$1\" src=\"$2\" style=\"max-width:100%\"/>");
        // 链接 [text](url)
        t = t.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)",
                "<a href=\"$2\">$1</a>");
        // 粗体 **x** / __x__
        t = t.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        t = t.replaceAll("__([^_]+)__", "<b>$1</b>");
        // 斜体 *x* / _x_
        t = t.replaceAll("\\*([^*]+)\\*", "<i>$1</i>");
        t = t.replaceAll("(^|[^\\w])_([^_]+)_", "$1<i>$2</i>");
        // 删除线 ~~x~~
        t = t.replaceAll("~~([^~]+)~~", "<s>$1</s>");

        // 还原行内代码
        for (int i = 0; i < codes.size(); i++) {
            t = t.replace("\u0000CODE" + i + "\u0000",
                    "<code>" + codes.get(i) + "</code>");
        }
        return t;
    }

    /** HTML 转义（&lt; &gt; &amp; &quot;） */
    private static String esc(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
