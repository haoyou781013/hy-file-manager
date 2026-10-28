package com.aliya.hyfilemgr.apkicon;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 resources.arsc 解析器：按资源 id 查文件路径。
 *
 * <p>结构：</p>
 * <pre>
 * ResTable_header
 *   ├─ ResStringPool  (全局值字符串池：图标路径就在这)
 *   ├─ ResTable_package
 *   │    ├─ ResStringPool (类型名池)
 *   │    ├─ ResTable_typeSpec
 *   │    └─ ResTable_type  (条目偏移数组 + 条目数据)
 *   └─ ...
 * </pre>
 *
 * <p>对每个 {@code ResTable_type} 的条目：若其值类型为 TYPE_STRING(0x03)，
 * valueData 即全局字符串池下标，取出后通常是 {@code res/mipmap-xxhdpi/ic_launcher.png}。</p>
 */
final class Arsc {

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_TABLE = 0x0002;
    private static final int CHUNK_PACKAGE = 0x0200;
    private static final int CHUNK_TYPE = 0x0201;
    private static final int TYPE_STRING = 0x03;

    private Arsc() { }

    /** 解析某资源 id 对应的所有文件路径（跨所有分辨率与配置） */
    static List<String> resolveFilePaths(byte[] data, int resId) {
        List<String> out = new ArrayList<>();
        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int fileSize = buf.getInt(4);

            int pkgId = (resId >>> 24) & 0xFF;
            int typeId = (resId >>> 16) & 0xFF;
            int entryId = resId & 0xFFFF;

            // 全局值字符串池
            List<String> globalPool = new ArrayList<>();

            int pos = 8;
            boolean inTargetPackage = false;
            while (pos + 8 <= Math.min(fileSize, data.length)) {
                int type = buf.getShort(pos) & 0xFFFF;
                int headerSize = buf.getShort(pos + 2) & 0xFFFF;
                int chunkSize = buf.getInt(pos + 4);
                if (chunkSize <= 0 || pos + chunkSize > data.length) break;

                if (type == CHUNK_STRING_POOL && globalPool.isEmpty()) {
                    globalPool = readStringPool(buf, pos, headerSize, chunkSize);
                } else if (type == CHUNK_PACKAGE) {
                    int id = buf.getInt(pos + 8);
                    inTargetPackage = (id == pkgId);
                    if (inTargetPackage) {
                        collectFromPackage(buf, pos, headerSize, chunkSize,
                                typeId, entryId, globalPool, out);
                    }
                }
                pos += chunkSize;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 遍历 package 内的 type chunk，收集目标 entry 的字符串值 */
    private static void collectFromPackage(ByteBuffer buf, int pkgPos, int pkgHeaderSize,
                                           int pkgChunkSize, int typeId, int entryId,
                                           List<String> globalPool, List<String> out) {
        int p = pkgPos + pkgHeaderSize;
        int end = pkgPos + pkgChunkSize;
        while (p + 8 <= end && p + 8 <= buf.limit()) {
            int type = buf.getShort(p) & 0xFFFF;
            int headerSize = buf.getShort(p + 2) & 0xFFFF;
            int chunkSize = buf.getInt(p + 4);
            if (chunkSize <= 0 || p + chunkSize > end) break;

            if (type == CHUNK_TYPE) {
                int id = buf.get(p + 8) & 0xFF;          // type id
                int entryCount = buf.getInt(p + 12);
                int entriesStart = buf.getInt(p + 16);
                if (id == typeId && entryId < entryCount) {
                    // 偏移数组起始 = chunk 头 + headerSize
                    int offPos = p + headerSize + entryId * 4;
                    if (offPos + 4 <= p + chunkSize) {
                        int off = buf.getInt(offPos);
                        if (off != -1 && off != 0xFFFFFFFF) {
                            int ePos = p + entriesStart + off;
                            if (ePos + 8 <= p + chunkSize) {
                                int vsize = buf.getShort(ePos) & 0xFFFF;
                                int vtype = buf.get(ePos + 3) & 0xFF;
                                int vdata = buf.getInt(ePos + 4);
                                if (vtype == TYPE_STRING && vdata >= 0
                                        && vdata < globalPool.size()) {
                                    String s = globalPool.get(vdata);
                                    if (s != null && !s.isEmpty() && !out.contains(s)) {
                                        out.add(s);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            p += chunkSize;
        }
    }

    /** 读字符串池（复用 Axml 的格式） */
    private static List<String> readStringPool(ByteBuffer buf, int chunkPos,
                                               int headerSize, int chunkSize) {
        List<String> out = new ArrayList<>();
        try {
            int stringCount = buf.getInt(chunkPos + 8);
            int flags = buf.getInt(chunkPos + 16);
            boolean utf8 = (flags & (1 << 8)) != 0;
            int stringsStart = buf.getInt(chunkPos + 20);
            int base = chunkPos + stringsStart;

            for (int i = 0; i < stringCount; i++) {
                int offPos = chunkPos + headerSize + i * 4;
                if (offPos + 4 > chunkPos + chunkSize) break;
                int off = buf.getInt(offPos);
                int p = base + off;
                if (p < 0 || p >= buf.limit()) {
                    out.add("");
                    continue;
                }
                out.add(utf8 ? readUtf8(buf, p) : readUtf16(buf, p));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String readUtf8(ByteBuffer buf, int p) {
        try {
            int[] r1 = readLen8(buf, p);
            int[] r2 = readLen8(buf, r1[1]);
            int byteLen = r2[0];
            int start = r2[1];
            byte[] bs = new byte[byteLen];
            for (int i = 0; i < byteLen; i++) bs[i] = buf.get(start + i);
            return new String(bs, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readUtf16(ByteBuffer buf, int p) {
        try {
            int len = buf.getShort(p) & 0xFFFF;
            int start = p + 2;
            char[] cs = new char[len];
            for (int i = 0; i < len; i++) cs[i] = buf.getChar(start + i * 2);
            return new String(cs);
        } catch (Throwable t) {
            return "";
        }
    }

    private static int[] readLen8(ByteBuffer buf, int p) {
        int b = buf.get(p) & 0xFF;
        if ((b & 0x80) == 0) return new int[]{b, p + 1};
        int b2 = buf.get(p + 1) & 0xFF;
        return new int[]{((b & 0x7F) << 8) | b2, p + 2};
    }
}
