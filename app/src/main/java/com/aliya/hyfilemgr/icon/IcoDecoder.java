package com.aliya.hyfilemgr.icon;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * ICO 图标解码器（纯 Java，零依赖）。
 *
 * <p>ICO 结构：</p>
 * <pre>
 * ICONDIR      6 字节：reserved(2)=0, type(2)=1, count(2)
 * ICONDIRENTRY 16 字节 × count：
 *   width(1) height(1) colorCount(1) reserved(1)
 *   planes(2) bitCount(2) bytesInRes(4) imageOffset(4)
 * 图像数据：BMP（含 AND 掩码）或 PNG（Vista+ 允许直接嵌 PNG）
 * </pre>
 *
 * <p><b>为什么要自己写</b>：Android 的 {@code BitmapFactory} 不支持 ICO，
 * 项目里 .ico 一直被归到图片扩展名，实际解码失败后只能回落成静态图标 ——
 * 用户看到的是「每个 ico 长得一样」。这个解码器同时给 PE（exe/dll）提取器复用。</p>
 *
 * <p><b>只取最大的一张</b>：ICO 里通常有 16/32/48/256 多档，解码全部纯属浪费内存；
 * 缩略图要的是「够清晰且不太大」，取最长边 ≤256 的最大一项即可。</p>
 */
public final class IcoDecoder {

    private IcoDecoder() { }

    /** 解析结果：像素位图 + 该图标在图集里的数量（用于诊断） */
    public static final class Result {
        public final Bitmap bitmap;
        public final int imageCount;
        Result(Bitmap bitmap, int imageCount) {
            this.bitmap = bitmap;
            this.imageCount = imageCount;
        }
    }

    /**
     * 从 .ico 文件解码。
     *
     * @param path  文件路径
     * @param maxPx 期望的最长边上限（像素）；≤0 表示不限
     * @return 失败返回 null（调用方静默回退类型图标）
     */
    public static Result decode(String path, int maxPx) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(path, "r");
            return decode(raf, maxPx);
        } catch (Throwable t) {
            return null;
        } finally {
            close(raf);
        }
    }

    /** 从已打开的 ICO 数据流解码（PE 提取器重组后可复用） */
    public static Result decode(byte[] icoBytes, int maxPx) {
        if (icoBytes == null || icoBytes.length < 22) return null;
        try {
            return decode(new ByteReader(icoBytes), maxPx);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Result decode(RandomAccessFile raf, int maxPx) throws Exception {
        byte[] head = new byte[6];
        raf.readFully(head);
        int type = u16(head, 2);
        int count = u16(head, 4);
        // type: 1=图标, 2=光标。只处理图标
        if (type != 1 || count <= 0 || count > 64) return null;

        int bestW = -1, bestH = -1, bestLen = 0;
        long bestOff = 0;
        int bestBpp = 0;

        byte[] ent = new byte[16];
        for (int i = 0; i < count; i++) {
            raf.readFully(ent);
            // 宽高字段为 0 表示 256
            int w = ent[0] == 0 ? 256 : (ent[0] & 0xFF);
            int h = ent[1] == 0 ? 256 : (ent[1] & 0xFF);
            int bpp = u16(ent, 6);
            int len = s32(ent, 8);
            long off = u32(ent, 12);
            if (len <= 0 || off <= 0) continue;
            // 选「最长边不超过 maxPx 里最大的一张」；都不满足时选最小的一张
            boolean fits = maxPx <= 0 || Math.max(w, h) <= maxPx;
            if (fits) {
                if (Math.max(w, h) > Math.max(bestW, bestH)) {
                    bestW = w; bestH = h; bestLen = len; bestOff = off; bestBpp = bpp;
                }
            } else if (bestW < 0) {
                // 记录一个兜底（后续若没有合适尺寸就用它，解码后再缩放）
                bestW = w; bestH = h; bestLen = len; bestOff = off; bestBpp = bpp;
            }
        }
        if (bestLen <= 0 || bestOff <= 0) return null;

        byte[] img = new byte[bestLen];
        raf.seek(bestOff);
        raf.readFully(img);
        Bitmap bmp = decodeImageData(img, bestW, bestH, bestBpp);
        if (bmp == null) return null;
        if (maxPx > 0) bmp = scaleDown(bmp, maxPx);
        return new Result(bmp, count);
    }

    /** 从内存字节解码（PE 提取器重组出的 ICO 也走这里） */
    private static Result decode(ByteReader r, int maxPx) throws Exception {
        r.seek(0);
        if (r.readU16() != 0) return null;   // reserved 必须 0
        int type = r.readU16();
        int count = r.readU16();
        if (type != 1 || count <= 0 || count > 64) return null;

        int bestW = -1, bestH = -1, bestLen = 0, bestOff = 0, bestBpp = 0;
        for (int i = 0; i < count; i++) {
            int w = r.readU8(); if (w == 0) w = 256;
            int h = r.readU8(); if (h == 0) h = 256;
            r.skip(2);                    // colorCount + reserved
            r.readU16();                  // planes
            int bpp = r.readU16();
            int len = (int) r.readU32();
            int off = (int) r.readU32();
            if (len <= 0 || off <= 0 || off + len > r.size()) continue;
            boolean fits = maxPx <= 0 || Math.max(w, h) <= maxPx;
            if (fits) {
                if (Math.max(w, h) > Math.max(bestW, bestH)) {
                    bestW = w; bestH = h; bestLen = len; bestOff = off; bestBpp = bpp;
                }
            } else if (bestW < 0) {
                bestW = w; bestH = h; bestLen = len; bestOff = off; bestBpp = bpp;
            }
        }
        if (bestLen <= 0) return null;

        byte[] img = new byte[bestLen];
        System.arraycopy(r.buf, bestOff, img, 0, bestLen);
        Bitmap bmp = decodeImageData(img, bestW, bestH, bestBpp);
        if (bmp == null) return null;
        if (maxPx > 0) bmp = scaleDown(bmp, maxPx);
        return new Result(bmp, count);
    }

    /**
     * 解码 ICO 里的单个图像数据。
     *
     * <p>两种载体：
     * ① PNG（Vista+ 常见于 256×256）——开头 8 字节 PNG 签名，直接 BitmapFactory
     * ② BMP（DIB，无文件头）——长度 ≥40 的 BITMAPINFOHEADER + 像素 + AND 掩码。
     *    DIB 的高度字段是「实际高度×2」（含掩码），必须除以 2 才能算对跨度。</p>
     */
    private static Bitmap decodeImageData(byte[] d, int w, int h, int bpp) {
        try {
            if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P'
                    && d[2] == 'N' && d[3] == 'G') {
                return BitmapFactory.decodeByteArray(d, 0, d.length);
            }
            if (d.length < 40) return null;
            int headerSize = s32(d, 0);
            if (headerSize < 40 || headerSize > d.length) return null;
            int width = s32(d, 4);
            int height = s32(d, 8) / 2;      // 含 AND 掩码 → 实际高度是一半
            int bitCount = u16(d, 14);
            int compression = s32(d, 16);
            if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return null;
            if (compression != 0) return null;   // 仅支持 BI_RGB（ICO 内几乎都是）
            if (bitCount != 32 && bitCount != 24 && bitCount != 8 && bitCount != 4) return null;

            int[] argb = new int[width * height];
            int pixelStart = headerSize;
            // 调色板（8bpp: 256*4, 4bpp: 16*4）
            int paletteEntries = bitCount <= 8 ? (1 << bitCount) : 0;
            int[] palette = new int[paletteEntries];
            for (int i = 0; i < paletteEntries; i++) {
                int o = headerSize + i * 4;
                if (o + 3 >= d.length) break;
                int b = d[o] & 0xFF, g = d[o + 1] & 0xFF, r = d[o + 2] & 0xFF;
                palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            pixelStart = headerSize + paletteEntries * 4;

            int lineBytes = ((width * bitCount + 31) / 32) * 4;   // 4 字节对齐
            // AND 掩码紧随像素数据
            int maskStart = pixelStart + lineBytes * height;
            int maskLineBytes = ((width + 31) / 32) * 4;

            for (int y = 0; y < height; y++) {
                int srcY = height - 1 - y;      // BMP 自下而上
                int lineOff = pixelStart + srcY * lineBytes;
                int outOff = y * width;
                if (lineOff + lineBytes > d.length) break;
                for (int x = 0; x < width; x++) {
                    int color;
                    if (bitCount == 32) {
                        int o = lineOff + x * 4;
                        int b = d[o] & 0xFF, g = d[o + 1] & 0xFF, r = d[o + 2] & 0xFF;
                        int a = d[o + 3] & 0xFF;
                        // 32bpp 的 alpha 全 0 是历史遗留（早期写 0 表示不透明）
                        if (a == 0) a = 0xFF;
                        color = (a << 24) | (r << 16) | (g << 8) | b;
                    } else if (bitCount == 24) {
                        int o = lineOff + x * 3;
                        int b = d[o] & 0xFF, g = d[o + 1] & 0xFF, r = d[o + 2] & 0xFF;
                        color = 0xFF000000 | (r << 16) | (g << 8) | b;
                    } else if (bitCount == 8) {
                        int idx = d[lineOff + x] & 0xFF;
                        color = idx < palette.length ? palette[idx] : 0xFF000000;
                    } else { // 4bpp
                        int b = d[lineOff + (x >> 1)] & 0xFF;
                        int idx = (x % 2 == 0) ? (b >> 4) : (b & 0x0F);
                        color = idx < palette.length ? palette[idx] : 0xFF000000;
                    }
                    argb[outOff + x] = color;
                }
            }

            // AND 掩码：低位=1 表示该像素透明。32bpp 自带 alpha 时掩码通常全 0，
            // 仍按规范应用一次（仅对 alpha 为 255 的像素生效，避免覆盖真实 alpha）
            if (maskStart + maskLineBytes * height <= d.length) {
                for (int y = 0; y < height; y++) {
                    int srcY = height - 1 - y;
                    int lineOff = maskStart + srcY * maskLineBytes;
                    for (int x = 0; x < width; x++) {
                        int byteIdx = lineOff + (x >> 3);
                        if (byteIdx >= d.length) continue;
                        boolean transparent = ((d[byteIdx] >> (7 - (x & 7))) & 1) == 1;
                        if (transparent) {
                            int o = y * width + x;
                            int c = argb[o];
                            if ((c >>> 24) == 0xFF) argb[o] = c & 0x00FFFFFF;   // 置全透明
                        }
                    }
                }
            }

            return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        int big = Math.max(w, h);
        if (big <= max) return src;
        float f = (float) max / big;
        Bitmap out = Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
        if (out != src) src.recycle();
        return out;
    }

    // ── 小工具 ──

    private static void close(RandomAccessFile r) {
        if (r != null) try { r.close(); } catch (Throwable ignored) { }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int s32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static long u32(byte[] b, int o) {
        return (long) s32(b, o) & 0xFFFFFFFFL;
    }

    /** 内存字节的顺序读取器（PE 提取器重组 ICO 后用） */
    private static final class ByteReader {
        final byte[] buf;
        int pos;
        ByteReader(byte[] b) { buf = b; }
        int size() { return buf.length; }
        void seek(int p) { pos = p; }
        void skip(int n) { pos += n; }
        int readU8() { return buf[pos++] & 0xFF; }
        int readU16() { int v = u16(buf, pos); pos += 2; return v; }
        long readU32() { long v = u32(buf, pos); pos += 4; return v; }
    }
}
