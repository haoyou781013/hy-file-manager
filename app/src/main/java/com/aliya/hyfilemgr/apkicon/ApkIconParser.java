package com.aliya.hyfilemgr.apkicon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 轻量 APK 图标解析器：不依赖 PackageManager，直接从 APK 文件字节解析。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>读 {@code AndroidManifest.xml}（二进制 AXML），取 {@code application} 的
 *       {@code android:icon} 属性（可能是资源引用 0x7f... 或字面路径）</li>
 *   <li>若是资源引用，读 {@code resources.arsc}，在资源表中查找该 id 对应的
 *       文件路径字符串（如 {@code res/mipmap-xxhdpi/ic_launcher.png}）</li>
 *   <li>从 zip 中取出该 PNG/WebP 字节，交给调用方解码</li>
 * </ol>
 *
 * <p>解析失败不抛异常，返回 null，调用方回退类型图标。</p>
 */
public final class ApkIconParser {

    private ApkIconParser() { }

    /** 解析结果：图标文件在 APK 内的路径 + 原始字节 */
    public static final class Result {
        public final String entryName;
        public final byte[] data;
        Result(String entryName, byte[] data) {
            this.entryName = entryName;
            this.data = data;
        }
    }

    /**
     * 解析 APK 图标。
     *
     * @param apkPath APK 文件绝对路径
     * @return 图标字节；失败返回 null
     */
    public static Result parse(String apkPath) {
        try (ZipFile zf = new ZipFile(apkPath)) {
            // ① 从 AndroidManifest 拿 icon 属性值
            byte[] manifest = readEntry(zf, "AndroidManifest.xml");
            if (manifest == null) return null;
            Integer iconResId = Axml.firstApplicationIconResId(manifest);
            String iconPath = Axml.firstApplicationIconString(manifest);

            // ② 资源引用 → 到 resources.arsc 查路径
            if (iconResId != null && iconResId != 0) {
                byte[] arsc = readEntry(zf, "resources.arsc");
                if (arsc != null) {
                    List<String> paths = Arsc.resolveFilePaths(arsc, iconResId);
                    Result r = pickExisting(zf, paths);
                    if (r != null) return r;
                }
            }
            // ③ 字面路径（少数 APK 直接写 drawable/xxx.png）
            if (iconPath != null && !iconPath.isEmpty()) {
                Result r = pickExisting(zf, java.util.Collections.singletonList(iconPath));
                if (r != null) return r;
            }
            // ④ 兜底：约定路径
            Result r = pickExisting(zf, commonIconPaths());
            if (r != null) return r;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 常见图标位置兜底（按分辨率从高到低） */
    private static List<String> commonIconPaths() {
        List<String> out = new ArrayList<>();
        String[] dens = {"xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "anydpi"};
        String[] names = {"ic_launcher", "ic_launcher_round", "app_icon", "icon", "logo"};
        String[] exts = {"png", "webp", "jpg"};
        for (String d : dens) {
            for (String n : names) {
                for (String e : exts) {
                    out.add("res/mipmap-" + d + "/" + n + "." + e);
                    out.add("res/drawable-" + d + "/" + n + "." + e);
                }
            }
        }
        for (String n : names) {
            for (String e : exts) {
                out.add("res/mipmap/" + n + "." + e);
                out.add("res/drawable/" + n + "." + e);
            }
        }
        return out;
    }

    /** 按顺序找第一个真实存在的 zip 条目并取出字节 */
    private static Result pickExisting(ZipFile zf, List<String> paths) {
        if (paths == null) return null;
        for (String p : paths) {
            if (p == null || p.isEmpty()) continue;
            byte[] d = readEntry(zf, p);
            if (d != null && d.length > 0) return new Result(p, d);
        }
        return null;
    }

    private static byte[] readEntry(ZipFile zf, String name) {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        try (InputStream is = zf.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, (int) e.getSize()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }
}
