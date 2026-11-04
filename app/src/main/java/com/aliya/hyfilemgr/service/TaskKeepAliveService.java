package com.aliya.hyfilemgr.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.aliya.hyfilemgr.R;

/**
 * 长时间文件操作的保活前台服务。
 *
 * <p><b>为什么需要它</b>：压缩/解压/复制大目录都跑在
 * {@code TASK_QUEUE} 线程池里，没有前台服务时 App 切到后台，
 * 系统随时可能杀掉进程 —— 结果是留下半截压缩包（数据损坏），
 * 用户回来一脸问号。</p>
 *
 * <p><b>关于 Android 15 的 dataSync 限制</b>：官方文档写明
 * targeting API 35+ 的 App，dataSync 前台服务在 24 小时内最多跑 6 小时，
 * 超时会调用 {@code Service.onTimeout(int,int)}，几秒内不 stopSelf 就抛
 * RemoteServiceException。我们 {@code targetSdk = 34}，暂不受该限制；
 * 但一旦升到 35，就要在 onTimeout 里主动停止并提示用户。
 * 这里预先实现了 onTimeout（API 35+ 才回调），升级时不用再改。</p>
 */
public class TaskKeepAliveService extends Service {

    /** 通知渠道：低优先级，不出声、不弹横幅，只在通知栏占一行 */
    private static final String CHANNEL_ID = "hy_task_keepalive";
    private static final int NOTIFICATION_ID = 0x4B41;   // 'KA'

    /**
     * 当前有多少个任务在跑；归零即停服务。
     *
     * <p>必须原子：acquire/release 从 TASK_QUEUE 线程调用，
     * resetCount 却从 onAttach 的主线程调用 —— 二者并发时
     * 普通 int 的 ++/-- 会丢更新，计数可能变成负数，
     * 于是 release 里 {@code runningTasks > 0} 判断永远为假，
     * 服务永不停止，通知栏常驻「正在处理文件」且用户划不掉（setOngoing）。</p>
     */
    private static final java.util.concurrent.atomic.AtomicInteger runningTasks =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // ── 静态入口：由 FileManagerModule 的 runTask 调用 ──

    /** 有任务开始时调用（幂等，可重复调） */
    public static void acquire(Context context, String title) {
        if (context == null) return;
        runningTasks.incrementAndGet();
        try {
            Intent i = new Intent(context, TaskKeepAliveService.class);
            i.putExtra("title", title == null ? "正在处理文件" : title);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable ignored) {
            // 后台启动前台服务被系统拒绝（Android 12+ 有后台启动限制）时
            // 不能崩，任务照跑，只是失去保活
            runningTasks.decrementAndGet();
        }
    }

    /** 任务结束时调用（幂等） */
    public static void release(Context context) {
        int left = runningTasks.updateAndGet(v -> v > 0 ? v - 1 : 0);
        if (left > 0 || context == null) return;
        try {
            context.stopService(new Intent(context, TaskKeepAliveService.class));
        } catch (Throwable ignored) {
        }
    }

    /** 进程被回收后 runningTasks 归零，避免下一次 acquire 计数错乱 */
    public static void resetCount() {
        runningTasks.set(0);
    }

    // ── Service 生命周期 ──

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = intent == null ? null : intent.getStringExtra("title");
        Notification n = buildNotification(title == null ? "正在处理文件" : title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // dataSync：文件读写类任务的正确类型。
            // 不要用 specialUse —— Google Play 对该类型有额外审查。
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        // START_NOT_STICKY：进程被杀后不要自动重启一个空服务
        return START_NOT_STICKY;
    }

    /**
     * Android 15（API 35）起 dataSync 累计 6 小时/24 小时会回调这里。
     * 我们 targetSdk=34 暂不触发，但先实现好，升级 targetSdk 时零改动。
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        if (Build.VERSION.SDK_INT >= 35) {
            super.onTimeout(startId, fgsType);
            stopSelf();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;   // 不需要绑定
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "文件处理任务", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("压缩、解压、复制等耗时操作进行中时显示");
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String title) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("完成后自动消失，切到后台也不会中断")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)          // 用户不能划掉
                .setSilent(true)           // 无声
                .setOnlyAlertOnce(true);
        return b.build();
    }
}
