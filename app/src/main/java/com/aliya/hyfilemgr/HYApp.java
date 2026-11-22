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
            //
            // ⚠ 必须用 RECEIVER_NOT_EXPORTED：
            // 这是个纯调试用的 receiver，唯一用途是让开发者手动触发一次崩溃
            // 来验证崩溃捕获链路。用 EXPORTED 意味着**手机上任何应用**都能
            // 发这条广播让本应用崩溃 —— 即使是 debug 包也不该开这个口子。
            //
            // 代价：`adb shell am broadcast` 从 shell 发送时，
            // NOT_EXPORTED 的 receiver 收不到。需要调试时改用：
            //   adb shell am broadcast --user 0 -a com.aliya.hyfilemgr.DEBUG_CRASH \
            //       -p com.aliya.hyfilemgr
            // 或直接在代码里断点触发。安全优先于调试便利。
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debugCrash, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerLegacyReceiver(debugCrash, f);
            }
        }
    }

    /**
     * API &lt; 33 的两参 registerReceiver。
     *
     * <p>该重载在旧系统上隐含「导出」语义，但 Android 13 之前没有
     * 强制 flag 的安全要求，且本 receiver 只在 debug 包注册。
     * 单独抽成方法是为了把 {@code @SuppressLint} 的作用域限制在这一行，
     * 而不是给整个 onCreate 加抑制。</p>
     */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(android.content.BroadcastReceiver r,
                                        android.content.IntentFilter f) {
        registerReceiver(r, f);
    }
}
