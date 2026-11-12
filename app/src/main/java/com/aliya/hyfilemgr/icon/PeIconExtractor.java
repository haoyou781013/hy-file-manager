package com.aliya.hyfilemgr.icon;

import android.graphics.Bitmap;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows PE 可执行文件（.exe / .dll / .mun）的图标提取器（纯 Java，零依赖）。
 *
 * <p><b>原理</b>：图标存在 PE 的 {@code .rsrc} 节里，分两级资源：
 * <ul>
 *   <li>{@code RT_GROUP_ICON}(14) —— 图标组目录（GRPICONDIR），列出组内各尺寸图像</li>
 *   <li>{@code RT_ICON}(3) —— 各尺寸的实际图像数据（DIB 或 PNG）</li>
 * </ul>
 * 提取流程：DOS 头 → PE 头 → 节表 → 定位 .rsrc → 资源目录树（type→name/ID→language）
 * → 找第一个 RT_GROUP_ICON → 读成员表 → 按 ID 取 RT_ICON 数据 → 重组成标准 ICO
 * → 交 {@link IcoDecoder} 解码。</p>
 *
 * <p><b>为什么重组 ICO</b>：PE 里组目录用「资源 ID」引用图像，ICO 文件用「文件偏移」。
 * 直接把字节拼起来行不通，必须重算偏移表 —— 这也是没法「只取一段字节」的原因。</p>
 *
 * <p><b>安全边界</b>：只读文件头与 .rsrc 节，绝不加载/执行 PE 内容；
 * 所有长度字段做上界校验，畸形文件一律返回 null（调用方回退静态图标）。</p>
 */
public final class PeIconExtractor {

    /** 资源类型：图标图像 */
    private static final int RT_ICON = 3;
    /** 资源类型：图标组目录 */
    private static final int RT_GROUP_ICON = 14;

    /** 单次读取的资源数据上限（防畸形文件声明超大长度撑爆内存） */
    private static final int MAX_RES_BYTES = 4 * 1024 * 1024;

    private PeIconExtractor() { }

    /** 扩展名是否可由本提取器处理 */
    public static boolean supports(String name) {
        if (name == null) return false;
        String low = name.toLowerCase(java.util.Locale.ROOT);
        return low.endsWith(".exe") || low.endsWith(".dll")
                || low.endsWith(".mun") || low.endsWith(".cpl");
    }

    /**
     * 提取图标。
     *
     * <p><b>字节序坑</b>：{@link RandomAccessFile#readInt()} / {@code readUnsignedShort()}
     * 都是<b>大端</b>，而 PE/ICO 全部是<b>小端</b>。直接用会把 'MZ'(0x5A4D) 读成 0x4D5A，
     * 导致所有文件都在第一步被判定为非 PE。这里统一走 {@link #le16}/{@link #le32}。</p>
     *
     * @param path  文件路径
     * @param maxPx 最长边上限（像素），≤0 不限
     * @return 失败返回 null（加壳/畸形/无图标资源都走这里）
     */
    public static Bitmap extractIcon(String path, int maxPx) {
        RandomAccessFile raf = null;
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() < 0x40) return null;
            raf = new RandomAccessFile(f, "r");

            // ── ① DOS 头：'MZ' + e_lfanew(0x3C) 指向 PE 签名 ──
            if (le16(raf) != 0x5A4D) return null;   // "MZ"（小端）
            raf.seek(0x3C);
            int peOff = le32(raf);
            if (peOff <= 0 || peOff > f.length() - 24) return null;

            // ── ② PE 签名 + COFF 头 ──
            raf.seek(peOff);
            if (le32(raf) != 0x00004550) return null;          // "PE\0\0"
            raf.skipBytes(2);                                     // Machine
            int numSections = le16(raf);
            raf.skipBytes(12);                                    // TimeDateStamp..SizeOfOptionalHeader 前
            int optHeaderSize = le16(raf);
            raf.skipBytes(2);                                     // Characteristics
            if (numSections <= 0 || numSections > 96) return null;

            // ── ③ 可选头：magic 判断 PE32/PE32+（ImageBase 与数据目录偏移不同）──
            long optStart = raf.getFilePointer();
            raf.seek(optStart);
            int magic = le16(raf);
            boolean pe32Plus = magic == 0x20B;
            if (magic != 0x10B && magic != 0x20B) return null;
            // 数据目录起点：PE32 = opt+96，PE32+ = opt+112
            long dataDirStart = optStart + (pe32Plus ? 112 : 96);

            // ── ④ 数据目录第 2 项 = 资源表（索引 2）──
            raf.seek(dataDirStart + 2 * 8);
            int rsrcRva = le32(raf);
            int rsrcSize = le32(raf);
            if (rsrcRva <= 0 || rsrcSize <= 0) return null;

            // ── ⑤ 节表：找包含 rsrcRva 的节，换算 文件偏移 ──
            long secTable = optStart + optHeaderSize;
            int rsrcRawPtr = -1;
            for (int i = 0; i < numSections; i++) {
                long secOff = secTable + (long) i * 40;
                raf.seek(secOff);
                raf.skipBytes(8);                                 // 节名
                int virtualSize = le32(raf);
                int virtualAddress = le32(raf);
                int sizeOfRawData = le32(raf);
                int pointerToRawData = le32(raf);
                int span = Math.max(virtualSize, sizeOfRawData);
                if (rsrcRva >= virtualAddress && rsrcRva < virtualAddress + span) {
                    rsrcRawPtr = pointerToRawData + (rsrcRva - virtualAddress);
                    break;
                }
            }
            if (rsrcRawPtr <= 0) return null;

            // ── ⑥ 资源目录树：找 RT_GROUP_ICON 的第一个组 ──
            //
            // ⚠ 两套偏移语义必须分清（实测 notepad++.exe 验证）：
            //   ① 目录项的 OffsetToDirectory —— 相对 **.rsrc 节起点**
            //   ② 数据项的 OffsetToData —— 真正的 RVA，需减 rsrcRva 才是节内偏移
            // 写混会得到负偏移或读到文件尾部之外（两种都实测出现过）。
            int groupDataOff = -1;
            int groupDataLen = 0;
            List<int[]> iconEntries = new ArrayList<>();   // [nameId, fileOffset, length]

            // 第一层：type 目录项
            int typeDirCount = dirEntryCount(raf, rsrcRawPtr);
            for (int i = 0; i < typeDirCount && i < 256; i++) {
                int[] te = readDirEntry(raf, rsrcRawPtr + 16 + i * 8);
                if (te[0] != RT_GROUP_ICON && te[0] != RT_ICON) continue;
                int subDirOff = te[1];
                if (subDirOff <= 0) continue;
                int subDirAbs = rsrcRawPtr + subDirOff;       // 目录项：节内偏移
                // 第二层：name/ID 目录项
                int nameCount = dirEntryCount(raf, subDirAbs);
                for (int j = 0; j < nameCount && j < 256; j++) {
                    int[] ne = readDirEntry(raf, subDirAbs + 16 + j * 8);
                    int nameOff = ne[1];
                    if (nameOff <= 0) continue;
                    int nameAbs = rsrcRawPtr + nameOff;        // 目录项：节内偏移
                    // 第三层：language 目录项 → 数据项
                    int langCount = dirEntryCount(raf, nameAbs);
                    if (langCount <= 0) continue;
                    int[] le = readDirEntry(raf, nameAbs + 16);
                    if (le[1] <= 0) continue;
                    int dataAbs = rsrcRawPtr + le[1];          // 目录项：节内偏移
                    raf.seek(dataAbs);
                    int dataRva = le32(raf);                   // 数据项：真 RVA
                    int dataSize = le32(raf);
                    if (dataRva <= 0 || dataSize <= 0 || dataSize > MAX_RES_BYTES) continue;
                    int dataFileOff = rsrcRawPtr + (dataRva - rsrcRva);
                    if (dataFileOff <= 0 || dataFileOff + dataSize > f.length()) continue;

                    if (te[0] == RT_GROUP_ICON) {
                        if (groupDataOff < 0) {          // 只取第一个组（Windows 行为一致）
                            groupDataOff = dataFileOff;
                            groupDataLen = dataSize;
                        }
                    } else {
                        iconEntries.add(new int[]{ne[0], dataFileOff, dataSize});
                    }
                }
            }
            if (groupDataOff < 0 || groupDataLen < 6) return null;

            // ── ⑦ 读组目录：reserved(2) type(2) count(2) + count×14 ──
            raf.seek(groupDataOff);
            byte[] g = new byte[groupDataLen];
            raf.readFully(g);
            int gCount = u16(g, 4);
            if (gCount <= 0 || gCount > 64) return null;
            if (6 + gCount * 14 > g.length) return null;

            // ── ⑧ 重组标准 ICO：头 6 + 目录 16×n + 图像数据 ──
            java.io.ByteArrayOutputStream dirOut = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream imgOut = new java.io.ByteArrayOutputStream();
            int dataOffset = 6 + gCount * 16;
            int written = 0;
            for (int i = 0; i < gCount; i++) {
                int o = 6 + i * 14;
                int w = g[o] & 0xFF;
                int h = g[o + 1] & 0xFF;
                int colorCount = g[o + 2] & 0xFF;
                int reserved = g[o + 3] & 0xFF;
                int planes = u16(g, o + 4);
                int bitCount = u16(g, o + 6);
                int bytesInRes = s32(g, o + 8);
                int iconId = u16(g, o + 12);      // ★ 这里是「被引用图标资源的 ID」

                int[] entry = findIcon(iconEntries, iconId);
                if (entry == null) continue;
                int off = entry[1], len = entry[2];
                if (len > MAX_RES_BYTES) continue;

                raf.seek(off);
                byte[] img = new byte[len];
                raf.readFully(img);

                // ICONDIRENTRY：w h colorCount reserved planes bitCount bytesInRes imageOffset
                dirOut.write(w);
                dirOut.write(h);
                dirOut.write(colorCount);
                dirOut.write(reserved);
                dirOut.write(planes & 0xFF);
                dirOut.write((planes >> 8) & 0xFF);
                dirOut.write(bitCount & 0xFF);
                dirOut.write((bitCount >> 8) & 0xFF);
                int curOff = dataOffset + imgOut.size();
                dirOut.write(bytesInRes & 0xFF);
                dirOut.write((bytesInRes >> 8) & 0xFF);
                dirOut.write((bytesInRes >> 16) & 0xFF);
                dirOut.write((bytesInRes >>> 24) & 0xFF);
                dirOut.write(curOff & 0xFF);
                dirOut.write((curOff >> 8) & 0xFF);
                dirOut.write((curOff >> 16) & 0xFF);
                dirOut.write((curOff >>> 24) & 0xFF);
                imgOut.write(img);
                written++;
            }
            if (written == 0) return null;

            // 写完整 ICO（count 用实际写入数）
            java.io.ByteArrayOutputStream ico = new java.io.ByteArrayOutputStream();
            ico.write(0); ico.write(0);                       // reserved
            ico.write(1); ico.write(0);                       // type = 1
            ico.write(written & 0xFF);
            ico.write((written >> 8) & 0xFF);
            byte[] dirBytes = dirOut.toByteArray();
            ico.write(dirBytes, 0, dirBytes.length);
            byte[] imgBytes = imgOut.toByteArray();
            ico.write(imgBytes, 0, imgBytes.length);

            // ── ⑨ 交 ICO 解码器（尺寸选择与位图解码都在那里）──
            IcoDecoder.Result r = IcoDecoder.decode(ico.toByteArray(), maxPx);
            return r == null ? null : r.bitmap;
        } catch (Throwable t) {
            return null;   // 加壳/畸形/半截文件：静默回退
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignored) { }
        }
    }

    private static int[] findIcon(List<int[]> entries, int id) {
        for (int[] e : entries) {
            if (e[0] == id) return e;
        }
        // 部分文件组里引用 ID 与实际 ID 对不上（打包工具差异）：只有一个图标时直接用
        return entries.size() == 1 ? entries.get(0) : null;
    }

    /** 目录项数量（资源目录头：Characteristics(4) TimeDateStamp(4) Major(2) Minor(2) Name(2) Id(2) = 16 字节） */
    private static int dirEntryCount(RandomAccessFile raf, int absOff) throws Exception {
        if (absOff <= 0) return 0;
        raf.seek(absOff + 12);
        int named = le16(raf);
        int ids = le16(raf);
        return named + ids;
    }

    /**
     * 读一个目录项：{nameOrId, offsetToDirOrData}。
     * 最高位=1 表示 offset 指向子目录，=0 表示叶子数据项（数据项结构与目录不同）。
     */
    private static int[] readDirEntry(RandomAccessFile raf, int absOff) throws Exception {
        raf.seek(absOff);
        int nameField = le32(raf);
        int offsetField = le32(raf);
        int nameOrId = (nameField & 0x80000000) != 0 ? -1 : (nameField & 0xFFFF);
        // 高位标记「子目录」，取低 31 位作为 RVA
        int off = offsetField & 0x7FFFFFFF;
        return new int[]{nameOrId, off, (offsetField & 0x80000000) != 0 ? 1 : 0};
    }

    // ── 小端读取（RandomAccessFile 默认大端，PE 是小端）──

    private static int le16(RandomAccessFile raf) throws Exception {
        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int le32(RandomAccessFile raf) throws Exception {
        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        int b3 = raf.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int s32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
