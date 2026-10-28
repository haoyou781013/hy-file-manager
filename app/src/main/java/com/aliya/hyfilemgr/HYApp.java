package com.aliya.hyfilemgr;

import android.app.Application;

import com.aliya.hyfilemgr.crash.CrashHandler;

/**
 * 应用入口：安装全局崩溃捕获。
 *
 * <p>任意功能内部崩溃后由 {@link CrashHandler} 接管，落盘日志并冷启动崩溃页。</p>
 */
public class HYApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);

        // ── 调试：验证崩溃框架（仅 debug 包生效）──
        // adb shell am broadcast -a com.aliya.hyfilemgr.DEBUG_CRASH
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    throw new RuntimeException("调试崩溃：由 DEBUG_CRASH 广播触发");
                }
            }, new android.content.IntentFilter("com.aliya.hyfilemgr.DEBUG_CRASH"),
                    android.content.Context.RECEIVER_EXPORTED);
        }
    }
}
