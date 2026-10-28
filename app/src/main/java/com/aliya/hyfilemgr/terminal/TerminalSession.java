package com.aliya.hyfilemgr.terminal;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 轻量终端会话：在 Android 上拉起 {@code /system/bin/sh} 并做 I/O 转发。
 *
 * <p>不依赖 proot / rootfs，直接使用系统 shell（toybox）。支持：</p>
 * <ul>
 *   <li>工作目录指定（打开终端时继承当前浏览路径）</li>
 *   <li>命令输入、输出流式回调</li>
 *   <li>环境变量注入（PATH / HOME / TERM / LANG）</li>
 *   <li>进程退出通知</li>
 * </ul>
 *
 * <p>回调均在主线程执行，调用方可直接更新 UI。</p>
 */
public class TerminalSession {

    /** 输出回调（主线程） */
    public interface Callback {
        /** 收到一段输出 */
        void onOutput(String text);
        /** 进程结束 */
        void onExit(int exitCode);
    }

    private static final String SHELL = "/system/bin/sh";

    private Process process;
    private OutputStream stdin;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    private final Callback callback;
    private final String workDir;

    public TerminalSession(String workDir, Callback callback) {
        this.workDir = workDir;
        this.callback = callback;
    }

    public boolean isRunning() {
        return running;
    }

    /** 启动 shell 会话 */
    public void start() {
        if (running) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(SHELL);
            pb.redirectErrorStream(true);   // stderr 并入 stdout，顺序才正确
            if (workDir != null && new File(workDir).isDirectory()) {
                pb.directory(new File(workDir));
            }
            // 环境变量：尽量贴近 Termux 的可用环境
            java.util.Map<String, String> env = pb.environment();
            String path = "/system/bin:/system/xbin:/vendor/bin:/sbin:/product/bin";
            env.put("PATH", path);
            env.put("HOME", workDir != null ? workDir : "/sdcard");
            env.put("TERM", "xterm-256color");
            env.put("LANG", "zh_CN.UTF-8");
            env.put("PS1", "\\$ ");
            // 交互式 + 无 profile，避免打印无关启动信息
            env.put("ENV", "");

            process = pb.start();
            stdin = process.getOutputStream();
            running = true;

            // 读线程：持续把输出送回主线程
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                int n;
                try (InputStream is = process.getInputStream()) {
                    while ((n = is.read(buf)) > 0) {
                        if (n == 0) continue;
                        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                        main.post(() -> callback.onOutput(s));
                    }
                } catch (IOException ignored) {
                } finally {
                    running = false;
                    int code = -1;
                    try {
                        code = process.waitFor();
                    } catch (Throwable ignored) {
                    }
                    final int fc = code;
                    main.post(() -> callback.onExit(fc));
                }
            }, "term-reader");
            reader.setDaemon(true);
            reader.start();

        } catch (Throwable t) {
            running = false;
            main.post(() -> callback.onOutput("\n[启动失败] " + t + "\n"));
            main.post(() -> callback.onExit(-1));
        }
    }

    /** 发送一行命令（自动补换行） */
    public void sendLine(String line) {
        send(line + "\n");
    }

    /** 发送原始文本（可用于 Tab 补全、Ctrl 组合键） */
    public void send(String text) {
        if (!running || stdin == null) return;
        try {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {
        }
    }

    /** 发送中断（Ctrl+C） */
    public void sendInterrupt() {
        send("\u0003");
    }

    /** 结束会话 */
    public void close() {
        running = false;
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {
        }
        try {
            if (process != null) process.destroy();
        } catch (Throwable ignored) {
        }
        process = null;
        stdin = null;
    }
}
