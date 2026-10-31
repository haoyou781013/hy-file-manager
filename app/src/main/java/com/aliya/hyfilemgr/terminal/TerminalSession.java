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
    /** 用户主动关闭的标志：区分「进程自己退出」与「我们关掉的」 */
    private volatile boolean closedByUser;
    /** 读线程引用，用于 close 时 interrupt */
    private Thread readerThread;

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
        closedByUser = false;
        try {
            ProcessBuilder pb = new ProcessBuilder(SHELL);
            pb.redirectErrorStream(true);   // stderr 并入 stdout，顺序才正确
            if (workDir != null && new File(workDir).isDirectory()) {
                pb.directory(new File(workDir));
            }
            java.util.Map<String, String> env = pb.environment();
            String path = "/system/bin:/system/xbin:/vendor/bin:/sbin:/product/bin";
            env.put("PATH", path);
            env.put("HOME", workDir != null ? workDir : "/sdcard");
            env.put("TERM", "xterm-256color");
            env.put("LANG", "zh_CN.UTF-8");
            env.put("PS1", "\\$ ");
            env.put("ENV", "");

            process = pb.start();
            stdin = process.getOutputStream();
            running = true;

            readerThread = new Thread(this::readLoop, "term-reader");
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (Throwable t) {
            running = false;
            main.post(() -> callback.onOutput("\n[启动失败] " + t + "\n"));
            main.post(() -> callback.onExit(-1));
        }
    }

    /**
     * 读循环。
     *
     * <p>用 {@link InputStreamReader} 而不是「byte[] 转 String」：
     * 后者会在 UTF-8 多字节字符跨越 4096 边界时把汉字切成两半，
     * 输出成乱码（中文/emoji 常见）。InputStreamReader 内部维护
     * 跨块状态，天然正确。</p>
     */
    private void readLoop() {
        final Process proc = process;
        if (proc == null) return;
        char[] buf = new char[2048];
        try (java.io.Reader is = new java.io.InputStreamReader(
                proc.getInputStream(), StandardCharsets.UTF_8)) {
            int n;
            while ((n = is.read(buf)) > 0) {
                if (closedByUser) break;
                String s = new String(buf, 0, n);
                main.post(() -> {
                    if (!closedByUser) callback.onOutput(s);
                });
            }
        } catch (IOException ignored) {
        } finally {
            running = false;
            // 用户主动关闭时不要回调 onExit：否则界面已经关了，
            // 还会弹一个「进程退出，退出码 -1」的提示
            if (closedByUser) return;
            int code = -1;
            try {
                code = proc.waitFor();
            } catch (Throwable ignored) {
            }
            final int fc = code;
            main.post(() -> {
                if (!closedByUser) callback.onExit(fc);
            });
        }
    }

    /** 发送一行命令（自动补换行） */
    public void sendLine(String line) {
        send(line + "\n");
    }

    /** 发送原始文本（可用于 Tab 补全、Ctrl 组合键） */
    /** 写入锁：stdin.write 不是线程安全的，并发写会让字节交错（输入乱序） */
    private final Object writeLock = new Object();

    public void send(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (writeLock) {
            java.io.OutputStream out = stdin;
            if (!running || out == null) return;
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                android.util.Log.e("HyTermSnd", "write fail", e);
            }
        }
    }

    /** 发送中断（Ctrl+C） */
    public void sendInterrupt() {
        send("\u0003");
    }

    /** 结束会话：先礼后兵（destroy → 等待 → destroyForcibly），并中断读线程 */
    public void close() {
        closedByUser = true;
        running = false;
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {
        }
        final Process proc = process;
        process = null;
        stdin = null;
        if (proc != null) {
            try {
                proc.destroy();
                // 给 300ms 体面退出机会；超时强杀，避免子进程成孤儿
                if (!proc.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (Throwable ignored) {
                try {
                    proc.destroyForcibly();
                } catch (Throwable ignored2) {
                }
            }
        }
        // 中断阻塞在 read() 上的读线程（此处无法真正唤醒系统调用，
        // 但能让它在下次循环时因 closedByUser 退出）
        Thread rt = readerThread;
        readerThread = null;
        if (rt != null) {
            rt.interrupt();
        }
    }
}
