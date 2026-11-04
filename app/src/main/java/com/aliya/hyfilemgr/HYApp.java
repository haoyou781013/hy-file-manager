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
            android.content.BroadcastReceiver debugCrash =
                    new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    throw new RuntimeException("调试崩溃：由 DEBUG_CRASH 广播触发");
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter("com.aliya.hyfilemgr.DEBUG_CRASH");
            // 三参 registerReceiver 是 API 33 新增的重载，minSdk=21 下
            // 在旧系统上直接调用会抛 NoSuchMethodError。必须按版本分支。
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debugCrash, f, android.content.Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(debugCrash, f);
            }
        }
    }
}
