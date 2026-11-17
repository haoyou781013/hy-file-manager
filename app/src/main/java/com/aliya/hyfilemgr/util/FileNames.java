package com.aliya.hyfilemgr.util;

import java.io.File;
import java.util.Locale;
import java.util.Set;

/**
 * 文件名与路径的纯函数工具。
 *
 * <p><b>为什么单独成类</b>：这些方法原先埋在
 * {@code FileManagerModule}（17000+ 行）里，虽然都是 {@code static}、
 * 零外部依赖、不碰 {@code Context}，却因为位置原因无法写单元测试
 * —— 要测一个 30 行的纯函数需要把整个 Module 拖进 Robolectric。</p>
 *
 * <p>现在它们只依赖 {@code java.io} / {@code java.util}，可以在 JVM 上直接测。</p>
 *
 * <p><b>注意</b>：带 SAF（{@code content://}）语义的方法（如 {@code isSelfNested}、
 * {@code baseNameOf}）仍留在模块里——它们依赖 {@code SafStrategy} 的 uri 解析，
 * 不属于「纯函数」范畴。</p>
 */
public final class FileNames {

    private FileNames() {}

    /**
     * 去掉路径末尾的斜杠（根目录 {@code "/"} 保留）。
     *
     * <p>{@code null} 会抛 NPE——调用方应先判空。这里不做宽容处理，
     * 因为历史上「悄悄把 null 当空串」掩盖过真实的 null 传递 bug。</p>
     */
    public static String trimSlash(String s) {
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * 目标位置是否已存在同名条目。
     *
     * @param dest     目标目录绝对值路径
     * @param name     候选文件名
     * @param existing 已知的子项名集合（SAF 目录用它避免逐项查询）；
     *                 传 {@code null} 时退化为真实文件系统 {@code exists()} 检查
     */
    public static boolean nameExists(String dest, String name, Set<String> existing) {
        if (existing != null) return existing.contains(name);
        return new File(dest, name).exists();
    }

    /**
     * 生成不冲突的文件名：重名时追加 {@code " (1)"}、{@code " (2)"} …
     *
     * <p>扩展名会被保留：{@code photo.jpg} → {@code photo (1).jpg}。
     * 以点开头的隐藏文件（{@code .gitignore}）不当作有扩展名处理。</p>
     *
     * @return 可用文件名；1000 次尝试仍冲突时返回原名（理论上不可达）
     */
    public static String uniqueName(String dest, String name, Set<String> existing) {
        if (!nameExists(dest, name, existing)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!nameExists(dest, cand, existing)) return cand;
        }
        return name;
    }

    /**
     * 文件名是否合法（Windows 语义，比 Linux 更严，为了跨平台兼容）。
     *
     * <p>拒绝：空名、{@code .}/{@code ..}、含 {@code / \ : * ? " < > |}、
     * 以点或空格结尾。最后两条是因为部分 SAF provider 会静默吞掉尾点/尾空格。</p>
     */
    public static boolean isValidFileName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        // 尾点/尾空格必须在 trim **之前**判断：旧实现先 trim 再判
        // endsWith(" ")，而 trim 已把尾空格删光 → 那个判断是死代码。
        //
        // ⚠ 但要如实说明：**当前所有调用方都会先 .trim() 再传进来**
        // （见 showNewFolderDialog:4189、rename:10563 等），所以这里的
        // 尾空格分支在实际链路上走不到，只是补全了函数自身的语义。
        // 尾点分支（endsWith(".")）是有效的——trim 不会删点。
        //
        // 若将来有调用方不 trim 就传原始输入，这里能兜住；
        // 那时尾空格会导致「校验通过但落盘名字被 provider 吞掉」的诡异问题。
        if (name.endsWith(".") || name.endsWith(" ")) return false;
        String n = name.trim();
        if (n.equals(".") || n.equals("..")) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }

    /**
     * 自然排序比较：数字块按数值比较，其余按字符序。
     *
     * <p>{@code file2 < file10}、{@code chapter1 < chapter10}——对齐主流文件管理器。</p>
     *
     * <p><b>入参必须已是小写</b>。早期版本在比较器内部对小写化，
     * 导致 n·log₂n 次比较产生 26 万个临时字符串（1 万文件目录）。
     * 现在由 {@code FileEntry.lowerName()} 缓存后传入。</p>
     */
    public static int naturalCompareLower(String s1, String s2) {
        int i1 = 0, i2 = 0;
        while (i1 < s1.length() && i2 < s2.length()) {
            char c1 = s1.charAt(i1);
            char c2 = s2.charAt(i2);
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                long n1 = 0, n2 = 0;
                while (i1 < s1.length() && Character.isDigit(s1.charAt(i1))) {
                    n1 = n1 * 10 + (s1.charAt(i1) - '0');
                    i1++;
                }
                while (i2 < s2.length() && Character.isDigit(s2.charAt(i2))) {
                    n2 = n2 * 10 + (s2.charAt(i2) - '0');
                    i2++;
                }
                if (n1 != n2) return Long.compare(n1, n2);
            } else {
                if (c1 != c2) {
                    // 数字块与非数字相邻时数字排前（对齐 Prism naturalCompare）
                    if (Character.isDigit(c1) && !Character.isDigit(c2)) return -1;
                    if (!Character.isDigit(c1) && Character.isDigit(c2)) return 1;
                    return Character.compare(c1, c2);
                }
                i1++;
                i2++;
            }
        }
        return Integer.compare(s1.length(), s2.length());
    }

    /**
     * 去掉压缩包扩展名，得到用作输出目录名的基名。
     *
     * <p>后缀表来自 {@link com.aliya.hyfilemgr.archive.ArchiveExtractor#NAME_SUFFIXES}
     * （全项目唯一来源）。</p>
     *
     * @return 基名；全部剥完为空时返回 {@code "extracted"}
     */
    public static String archiveBaseName(String name) {
        if (name == null || name.isEmpty()) return "extracted";
        String base = name;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : com.aliya.hyfilemgr.archive.ArchiveExtractor.NAME_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                base = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return base.isEmpty() ? "extracted" : base;
    }
}
