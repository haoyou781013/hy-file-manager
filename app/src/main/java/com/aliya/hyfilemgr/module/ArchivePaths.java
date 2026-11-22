package com.aliya.hyfilemgr.module;

/**
 * 压缩包路径解析与条目名规范化。
 *
 * <p><b>为什么单独成类</b>：{@code zip://} 虚拟路径的切分逻辑曾经是
 * 一次崩溃的来源 —— 后缀表内联维护、漏了 {@code .tbz/.tbz2/.txz/.tzst}，
 * 导致这些包切分失败后回退到首个 {@code /}，下游 {@code substring} 越界。
 * 这类「解析强约束字符串」的纯逻辑放在模块类里既难测也难维护，
 * 抽出来单独成类后可以对着边界值写用例。</p>
 *
 * <p><b>关键约束</b>：后缀表必须与
 * {@link com.aliya.hyfilemgr.archive.ArchiveExtractor#NAME_SUFFIXES} 保持同源，
 * 不再允许本类内联另一份。</p>
 */
public final class ArchivePaths {

    /** 压缩包内虚拟路径前缀 */
    public static final String PREFIX_ZIP = "zip://";

    private ArchivePaths() {}

    /**
     * 切分 {@code zip://<压缩包绝对路径>/<包内路径>}。
     *
     * @return {@code [压缩包路径, 包内相对路径]}；包内路径可能为空串
     */
    public static String[] splitZipPath(String virt) {
        String rest = virt.substring(PREFIX_ZIP.length());
        // 后缀表来自 ArchiveExtractor.NAME_SUFFIXES（全项目唯一来源）。
        // 曾因这里内联维护了一份、且漏了 .tbz/.tbz2/.txz/.tzst，
        // 导致这些包切分失败、点解压时越界崩溃。
        final String[] SUP = com.aliya.hyfilemgr.archive.ArchiveExtractor.NAME_SUFFIXES;
        // zip 路径自身可能含 "/"，逐个尝试已知后缀作为切分点
        int best = -1;
        for (int i = rest.length(); i > 0; i--) {
            char c = rest.charAt(i - 1);
            if (c != '/' && c != '\\') continue;
            String head = rest.substring(0, i - 1);
            String low = head.toLowerCase(java.util.Locale.ROOT);
            // 支持所有可浏览格式的切分：
            // 此前只认 zip/jar/apk，7z/tar/rar 会切分失败 →
            // zipPath 变成整个 "zip:///sdcard/x/test.7z/" → isFile()=false →
            // loadZip 提前 return "压缩包不存在"，表现为「打开后空白、连 .. 都没有」
            boolean hit = false;
            for (String s : SUP) {
                if (low.endsWith(s)) { hit = true; break; }
            }
            if (hit) {
                best = i - 1;
                break;
            }
        }
        if (best < 0) {
            // 防御：路径末尾没有 '/'（如 "zip:///sdcard/x/test.tbz"）。
            // 当前所有构造点都是 `PREFIX_ZIP + zipPath + "/"`（带尾斜杠），
            // 所以走不到这里；但旧的回退「第一个 '/'」会把 zipPath 切成空串，
            // 下游 substring 直接越界。这里先把整体当压缩包名试一次。
            String lowAll = rest.toLowerCase(java.util.Locale.ROOT);
            for (String s : SUP) {
                if (lowAll.endsWith(s)) return new String[]{rest, ""};
            }
            int sl = rest.indexOf('/');
            best = sl < 0 ? rest.length() : sl;
        }
        String zip = rest.substring(0, best);
        String inner = rest.substring(Math.min(best + 1, rest.length()));
        while (inner.startsWith("/")) inner = inner.substring(1);
        return new String[]{zip, inner};
    }

    /**
     * 规范化压缩包条目名。
     *
     * <p>处理：反斜杠转正斜杠、去掉 {@code ./} 与首尾斜杠、合并重复斜杠。
     * 目录条目常以 {@code /} 结尾（tar 的 {@code ./sub/}），必须去掉尾斜杠，
     * 否则按 {@code /} 切分时 rel 为空导致条目丢失。</p>
     */
    public static String normalizeEntryName(String raw) {
        if (raw == null) return "";
        String n = raw.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        while (n.contains("//")) n = n.replace("//", "/");
        // 目录条目常以 / 结尾（tar 的 "./sub/"）：去掉尾部斜杠，
        // 否则 "sub/" 在按 / 切分时 dirName 会取成 "sub" 但 rel 为空导致条目丢失
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n;
    }

    /** 是否疑似乱码（UTF-8 解 GBK 字节会产生 U+FFFD） */
    public static boolean containsGarbled(String s) {
        if (s == null || s.isEmpty()) return false;
        // UTF-8 解 GBK 字节时产生 U+FFFD。旧判据只看 c < 0x20 且跳过 \0，
        // 真实乱码是 U+FFFD 与高位字符 → 全部漏检，GBK 中文被当 UTF-8
        // 打开成一片替换符。
        int bad = 0;
        int total = Math.min(s.length(), 4096);
        for (int i = 0; i < total; i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') bad++;
            else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') bad++;
        }
        return total > 0 && bad * 200 > total;   // 超过 0.5%
    }

    /** 单流压缩格式（gz/bz2/xz/zst）：解出的内容不是 tar */
    public static boolean isSingleStreamExt(String ext) {
        return "gz".equals(ext) || "bz2".equals(ext) || "xz".equals(ext) || "zst".equals(ext);
    }

    /** 从 {@code tar.gz} 这类复合扩展名里取出内层扩展名；非复合返回 null */
    public static String tarInnerExt(String ext) {
        if ("tar".equals(ext)) return null;
        int dot = ext.indexOf('.');
        return dot < 0 ? null : ext.substring(dot + 1);
    }
}
