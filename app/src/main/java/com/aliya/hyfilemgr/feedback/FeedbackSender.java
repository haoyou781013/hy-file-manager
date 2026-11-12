package com.aliya.hyfilemgr.feedback;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用内问题反馈通道（提交到 GitHub Issues）。
 *
 * <p><b>为什么用 GitHub Issues</b>：用户提反馈不该先注册账号。Gitee 的 Issues
 * 对个人免费仓库只读不写（API 返回 {@code project or enterprise}），
 * GitHub 可以用一枚**权限受限的 token** 代提交，于是选它。</p>
 *
 * <p><b>token 的安全边界</b>（部署时已实测确认）：</p>
 * <ul>
 *   <li>Fine-grained PAT，只授权单个仓库 {@code hy-file-manager}</li>
 *   <li>只给 <b>Issues: Read and write</b>；其他权限全 No access</li>
 *   <li>90 天过期</li>
 *   <li>实测：能建 issue（201），写文件被拒（403 {@code Resource not accessible}）</li>
 * </ul>
 * <p>因此最坏后果是「有人刷垃圾 issue」，不涉及代码与仓库安全。
 * token 以分段 + 反转方式混淆存放，避免被 {@code strings} 一把抓走
 * （这只是提高门槛，反编译仍可取——真被滥用就换新 token）。</p>
 *
 * <p><b>提交失败必须给可用退路</b>：弱网/接口限流时弹窗展示完整内容，
 * 提供「复制」与「浏览器打开 GitHub 新建 issue」，不让用户白写一遍。</p>
 */
public final class FeedbackSender {

    private FeedbackSender() { }

    /** 仓库（只授权了这一个） */
    private static final String REPO = "haoyou781013/hy-file-manager";
    private static final String API = "https://api.github.com/repos/" + REPO + "/issues";

    /** 反馈类型 → label 名（仓库里已建好这两个 label） */
    public static final int TYPE_CRASH = 0;
    public static final int TYPE_SUGGESTION = 1;
    public static final int TYPE_OTHER = 2;

    private static String[] typeLabel(int type) {
        switch (type) {
            case TYPE_CRASH: return new String[]{"崩溃报告", "bug"};
            case TYPE_SUGGESTION: return new String[]{"用户反馈", "enhancement"};
            default: return new String[]{"用户反馈"};
        }
    }

    private static String typeTitle(int type) {
        switch (type) {
            case TYPE_CRASH: return "[崩溃] ";
            case TYPE_SUGGESTION: return "[建议] ";
            default: return "[反馈] ";
        }
    }

    /** 提交结果 */
    public static final class Result {
        public final boolean ok;
        /** 成功时是 issue 编号；失败时是错误说明 */
        public final String message;
        public final int issueNumber;

        Result(boolean ok, String message, int issueNumber) {
            this.ok = ok;
            this.message = message;
            this.issueNumber = issueNumber;
        }
    }

    /**
     * 同步提交（调用方必须在工作线程调用）。
     *
     * @param type   反馈类型（TYPE_*）
     * @param text   用户填写的内容
     * @param ctx    用于采集环境信息（可传 null 跳过）
     */
    public static Result submit(Context ctx, int type, String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Result(false, "内容不能为空", 0);
        }
        HttpURLConnection conn = null;
        try {
            String title = typeTitle(type) + firstLine(text);
            String body = buildBody(ctx, type, text);

            JSONObject payload = new JSONObject();
            payload.put("title", title);
            payload.put("body", body);
            // labels 需要 Issues 写权限；失败也不影响主体提交，故忽略错误码
            org.json.JSONArray labels = new org.json.JSONArray();
            for (String l : typeLabel(type)) labels.put(l);
            payload.put("labels", labels);

            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(API);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "HYFileManager-Feedback");

            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
            }

            int code = conn.getResponseCode();
            if (code == 201) {
                String resp = readAll(conn.getInputStream());
                JSONObject o = new JSONObject(resp);
                return new Result(true, "已提交", o.optInt("number", 0));
            }
            // 非 201：读错误说明（GitHub 会返回 message 字段）
            String err = "";
            try {
                InputStream es = conn.getErrorStream();
                if (es != null) {
                    String s = readAll(es);
                    JSONObject o = new JSONObject(s);
                    err = o.optString("message", s);
                }
            } catch (Throwable ignored) {
            }
            return new Result(false, "服务器返回 " + code
                    + (err.isEmpty() ? "" : "：" + err), 0);
        } catch (Throwable t) {
            String m = t.getMessage();
            return new Result(false, "网络异常" + (m == null ? "" : "：" + m), 0);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 标题取用户内容首行，截断到 60 字避免过长 */
    private static String firstLine(String text) {
        String s = text.trim();
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl);
        if (s.length() > 60) s = s.substring(0, 60) + "…";
        return s;
    }

    /** 正文 = 用户内容 + 折叠的环境信息（便于定位，用户不用手填） */
    private static String buildBody(Context ctx, int type, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append(text.trim()).append("\n\n");
        sb.append("---\n\n");
        sb.append("<details><summary>环境信息（自动采集）</summary>\n\n");
        sb.append("| 项 | 值 |\n|---|---|\n");
        sb.append("| 应用版本 | ").append(appVersion(ctx)).append(" |\n");
        sb.append("| 反馈类型 | ").append(typeName(type)).append(" |\n");
        sb.append("| 设备 | ").append(nullSafe(Build.MANUFACTURER)).append(' ')
                .append(nullSafe(Build.MODEL)).append(" |\n");
        sb.append("| 系统 | Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("） |\n");
        sb.append("| ABI | ").append(Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?").append(" |\n");
        sb.append("| 提交时间 | ").append(new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
                .append(" |\n");
        sb.append("\n</details>\n");
        return sb.toString();
    }

    private static String typeName(int type) {
        switch (type) {
            case TYPE_CRASH: return "崩溃";
            case TYPE_SUGGESTION: return "功能建议";
            default: return "其他";
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "?" : s;
    }

    private static String appVersion(Context ctx) {
        if (ctx == null) return "?";
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            String vn = pi.versionName == null ? "?" : pi.versionName;
            int vc = Build.VERSION.SDK_INT >= 28
                    ? (int) pi.getLongVersionCode() : pi.versionCode;
            return vn + "（" + vc + "）";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 降级：直接用浏览器打开 GitHub 新建 issue 页面（内容需用户粘贴） */
    public static void openWebFallback(Context ctx, String text) {
        try {
            String url = "https://github.com/" + REPO + "/issues/new?title="
                    + Uri.encode(firstLine(text == null ? "" : text));
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    // ── token（分段 + 反转存放，防 strings 直接抓）──

    /**
     * 受限 token 的混淆形式。
     *
     * <p>做法：原 token 反转后切成 5 段乱序存放，运行时按顺序拼接再反转。
     * 目的仅是**提高门槛**：让直接对 APK 做 {@code strings | grep github_pat} 拿不到。
     * 反编译仍可取（任何内置密钥都如此），因此 token 本身已限死
     * 单仓库 + 仅 issues 写 + 90 天过期——泄露代价是「被刷 issue」，
     * 不是代码/仓库安全。被滥用就吊销重发并更新此段。</p>
     */
    private static String token() {
        String[] parts = {
                "eqjtCEXG7LI3BAOIoWX",
                "WHylqLHlno3ZhZSEMbd",
                "vho7TTd3TL1xxYWyNNh",
                "dB_lRZyBPAiIuu40YB2",
                "EXPC11_tap_buhtig",
        };
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        return sb.reverse().toString();
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
