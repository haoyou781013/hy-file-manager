package com.aliya.hyfilemgr.apkicon;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Android 二进制 XML（AXML）解析器。
 *
 * <p>只做一件事：找到 {@code <application>} 元素的 {@code android:icon} 属性，
 * 返回其资源 id 与字面字符串（二者之一通常为空）。</p>
 *
 * <p>AXML 结构：文件头 + 字符串池(chunk 0x0001) + 依次的资源 chunk(0x0180 开始)。
 * 每个元素块包含 attrExt，属性数组每项 20 字节。</p>
 */
final class Axml {

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_RESOURCE_MAP = 0x0180;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    /** 属性值类型：字面字符串 */
    private static final int TYPE_STRING = 0x03;
    /** 资源 id 属性名 android:icon = 0x01010002 */
    private static final int ATTR_ICON = 0x01010002;

    private Axml() { }

    /** 取得 {@code <application>} 的 android:icon 资源 id；无则 null */
    static Integer firstApplicationIconResId(byte[] data) {
        try {
            return scanIcon(data, true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取得 {@code <application>} 的 android:icon 字面字符串；无则 null */
    static String firstApplicationIconString(byte[] data) {
        try {
            Object v = scanIconValue(data, false);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer scanIcon(byte[] data, boolean wantResId) {
        Object v = scanIconValue(data, wantResId);
        return v instanceof Integer ? (Integer) v : null;
    }

    /**
     * 扫描 AXML。
     * @param wantResId true 返回 Integer 资源 id，false 返回 String 字面值
     */
    private static Object scanIconValue(byte[] data, boolean wantResId) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int fileSize = buf.getInt(4);

        List<String> strings = new ArrayList<>();
        int pos = 8;                       // 跳过 ResChunk_header
        boolean inApplication = false;

        while (pos + 8 <= Math.min(fileSize, data.length)) {
            int type = buf.getShort(pos) & 0xFFFF;
            int headerSize = buf.getShort(pos + 2) & 0xFFFF;
            int chunkSize = buf.getInt(pos + 4);
            if (chunkSize <= 0 || pos + chunkSize > data.length) break;

            if (type == CHUNK_STRING_POOL) {
                strings = readStringPool(buf, pos, headerSize, chunkSize);
            } else if (type == CHUNK_START_ELEMENT) {
                // 元素名索引位于 pos+20（StartElement 的 name 字段）
                int nameIdx = buf.getInt(pos + 20);
                String tagName = nameIdx >= 0 && nameIdx < strings.size()
                        ? strings.get(nameIdx) : null;
                boolean isApplication = "application".equals(tagName);
                boolean isChildOfApp = inApplication && tagName != null;
                if (isApplication) {
                    inApplication = true;
                    Object v = readAttrIcon(buf, pos, headerSize, chunkSize, strings, wantResId);
                    if (v != null) return v;
                } else if (isChildOfApp) {
                    // 只关心 application 自身，子元素不看
                }
            } else if (type == 0x0103 /* END_ELEMENT */) {
                int nameIdx = buf.getInt(pos + 20);
                String tagName = nameIdx >= 0 && nameIdx < strings.size()
                        ? strings.get(nameIdx) : null;
                if ("application".equals(tagName)) inApplication = false;
            }
            pos += chunkSize;
        }
        return null;
    }

    /** 读取字符串池 chunk */
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

    /** UTF-8 变长字符串（Android 池格式） */
    private static String readUtf8(ByteBuffer buf, int p) {
        try {
            int[] r1 = readLen8(buf, p);      // 字符数
            int[] r2 = readLen8(buf, r1[1]);  // 字节数
            int byteLen = r2[0];
            int start = r2[1];
            byte[] bs = new byte[byteLen];
            for (int i = 0; i < byteLen; i++) bs[i] = buf.get(start + i);
            return new String(bs, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** UTF-16 字符串 */
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

    /** 读取 UTF-8 长度前缀，返回 {长度, 数据起始位置} */
    private static int[] readLen8(ByteBuffer buf, int p) {
        int b = buf.get(p) & 0xFF;
        if ((b & 0x80) == 0) return new int[]{b, p + 1};
        int b2 = buf.get(p + 1) & 0xFF;
        return new int[]{((b & 0x7F) << 8) | b2, p + 2};
    }

    /** 在 StartElement 的属性数组中查找 android:icon */
    private static Object readAttrIcon(ByteBuffer buf, int elemPos, int headerSize,
                                       int chunkSize, List<String> strings,
                                       boolean wantResId) {
        try {
            int attrStart = buf.getShort(elemPos + 24) & 0xFFFF;  // attributeStart
            int attrSize = buf.getShort(elemPos + 26) & 0xFFFF;   // attributeSize (20)
            int attrCount = buf.getShort(elemPos + 28) & 0xFFFF;  // attributeCount
            if (attrSize <= 0) attrSize = 20;
            int base = elemPos + headerSize + attrStart;

            for (int i = 0; i < attrCount; i++) {
                int a = base + i * attrSize;
                if (a + 20 > buf.limit()) break;
                int ns = buf.getInt(a);
                int name = buf.getInt(a + 4);
                int rawValue = buf.getInt(a + 8);
                int valueSize = buf.getShort(a + 12) & 0xFFFF;
                int valueType = buf.get(a + 15) & 0xFF;
                int valueData = buf.getInt(a + 16);

                // 属性名：优先用属性资源 id（ATTR_ICON），其次用名字字符串
                boolean isIcon = (name == ATTR_ICON);
                if (!isIcon) {
                    // resource map 可能把 name 映射为字符串索引
                    if (name >= 0 && name < strings.size()
                            && "icon".equals(strings.get(name))) {
                        isIcon = true;
                    }
                }
                if (!isIcon) continue;

                if (wantResId) {
                    // 资源引用类型 0x01 → valueData 是资源 id
                    if (valueType == 0x01) return valueData;
                } else {
                    if (valueType == TYPE_STRING && rawValue >= 0
                            && rawValue < strings.size()) {
                        String s = strings.get(rawValue);
                        if (s != null && !s.isEmpty()) return s;
                    }
                    if (valueType == TYPE_STRING && valueData >= 0
                            && valueData < strings.size()) {
                        String s = strings.get(valueData);
                        if (s != null && !s.isEmpty()) return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
