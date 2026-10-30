package com.aliya.hyfilemgr;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.aliya.hyfilemgr.module.FileManagerModule;

/**
 * HY 文件管理 · 入口 Activity
 *
 * <p>直接托管 HY_VQ 的 FileManagerModule（完整剥离版）：
 * 双窗格、多选、复制粘贴、ZIP 浏览、SAF、root、打开器、编辑器等全部沿用 HY_VQ 实现。</p>
 */
public class MainActivity extends AppCompatActivity {

    private FileManagerModule module;

    @Override
    protected void attachBaseContext(Context base) {
        // 读取已保存的 DPI 覆盖，在 Activity 创建前套用到 Context。
        // 必须在此处做：晚于 onCreate 时所有已布局的 View 尺寸不会重算。
        int dpi = base.getSharedPreferences("hyvq_filemgr", MODE_PRIVATE)
                .getInt("ui_dpi", 0);
        com.aliya.hyfilemgr.util.DpiUtils.setOverrideDpi(dpi);
        super.attachBaseContext(com.aliya.hyfilemgr.util.DpiUtils.wrap(base));
    }

    /** 主题偏好：0=默认，1=工业风格 */
    private static final String PREF_THEME = "ui_theme";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // setTheme 必须在 super.onCreate 之前，否则主题不会生效
        android.content.SharedPreferences sp =
                getSharedPreferences("hyvq_filemgr", MODE_PRIVATE);
        int theme = sp.getInt(PREF_THEME, 0);
        if (theme == 1) {
            // 工业风格：内置深色，不受明暗模式影响
            setTheme(R.style.Theme_HYFileManager_Industrial);
        } else {
            // 默认主题：按 ui_mode 选 0=跟随系统 / 1=亮 / 2=暗
            int mode = sp.getInt("ui_mode", 0);
            if (mode == 1) {
                setTheme(R.style.Theme_HYFileManager_Light);
            } else if (mode == 2) {
                setTheme(R.style.Theme_HYFileManager_Dark);
            }
        }
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        module = new FileManagerModule();
        // ⚠️ 必须先 onAttach：它内部调用 restoreState() 读取用户偏好
        // （隐藏文件 / 缩略图 / 排序 / 网格）。此前直接 createMainView 会跳过
        // 恢复流程，导致所有设置写入成功却永远不会被读回，表现为重启后丢失。
        module.onAttach(this, null);
        View v = module.createMainView(LayoutInflater.from(this), root);
        root.addView(v);

        setContentView(root);

        // 状态栏与标题栏同色：标题栏用的是 colorSurfaceContainer，
        // 这里同步状态栏，避免顶部出现一条不同色的横条。
        try {
            android.view.Window win = getWindow();
            int barColor = com.aliya.hyfilemgr.ui.UiKit.color(this,
                    com.google.android.material.R.attr.colorSurfaceContainer);
            win.setStatusBarColor(barColor);
            // 图标明暗：SYSTEM_UI_FLAG_LIGHT_STATUS_BAR 表示
            // 「状态栏背景是浅色，请用深色图标」——名字指的是背景，不是图标。
            // 旧代码把 dark 分支设成「清除该 flag」，等于深色底配浅色图标，
            // 方向是对的；但浅色底分支又设了 flag，两者都对——
            // 真正的问题是没判断实际背景亮度，Night 模式判断与实际 barColor 无关。
            // 改为按 barColor 的感知亮度决定，与主题无关，永远正确。
            int r = android.graphics.Color.red(barColor);
            int g2 = android.graphics.Color.green(barColor);
            int b2 = android.graphics.Color.blue(barColor);
            double luma = 0.299 * r + 0.587 * g2 + 0.114 * b2;
            boolean lightBackground = luma > 140;
            android.view.View decor = win.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (lightBackground) {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (module != null) module.onResume();
    }

    @Override
    protected void onPause() {
        if (module != null) module.onPause();
        super.onPause();
    }

    /**
     * SAF 授权结果转发（openSafAuthorize 用 REQ_SAF_MOUNT=1005 发起）。
     * 此前无接收点 → 授权结果丢失、takePersistableUriPermission 从未调用。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1005 || resultCode != RESULT_OK || data == null) return;
        android.net.Uri treeUri = data.getData();
        if (treeUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            android.util.Log.w("HYSAF", "takePersistableUriPermission failed", t);
        }
        com.aliya.hyfilemgr.module.FileManagerModule.onSafGrantedStatic();
    }

    @Override
    protected void onDestroy() {
        if (module != null) module.onDetach();
        super.onDestroy();
    }
}
