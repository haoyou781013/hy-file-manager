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

    /**
     * 系统照片选择器。
     *
     * <p>选背景图必须用它，不能用 {@code ACTION_OPEN_DOCUMENT} 或 {@code ACTION_PICK}：
     * <ul>
     *   <li>{@code ACTION_OPEN_DOCUMENT} 打开的是 DocumentsUI 文件树，不是相册；</li>
     *   <li>{@code ACTION_PICK} 带 CATEGORY_DEFAULT，多个应用能处理时会弹
     *       「打开方式」让用户再选一次，且各 ROM 的图库包名不同，
     *       硬编码包名只能覆盖部分机型。</li>
     * </ul>
     * {@code PickVisualMedia} 是 AndroidX 的跨版本封装：Android 13+ 走系统
     * Photo Picker，低版本自动回退到文档选择器，无需判断包名。
     * 注册必须在 onCreate 之前（成员初始化阶段），否则会抛 IllegalStateException。</p>
     */
    private final androidx.activity.result.ActivityResultLauncher<
            androidx.activity.result.PickVisualMediaRequest> bgPickerLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts
                            .PickVisualMedia(), uri -> {
                        if (module != null && uri != null) {
                            module.onPickBackgroundResult(uri);
                        }
                    });

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
    /**
     * 关闭 Material3 主题（兼容模式）。
     *
     * <p>部分旧 ROM / 深度定制系统的 AppCompat + Material3 主题存在资源
     * 解析冲突（Theme.Material3 父链断裂、colorSurfaceContainer* 缺失），
     * 开启 MD3 后会白屏或直接崩溃。这些机型需要退回 AppCompat 主题。</p>
     *
     * <p>默认 false（用 MD3，观感更好）。因为「打不开就进不去设置」，
     * 另有两道保险：① 上次启动若在此崩溃，自动降级一次；
     * ② 支持 adb 命令直接置位（见 README）。</p>
     */
    private static final String PREF_MD3_OFF = "md3_off";
    /** 启动标记：用于检测「开着 MD3 起不来」的情况 */
    private static final String PREF_MD3_BOOT_GUARD = "md3_boot_guard";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);

        markBootOk();
        buildUi();
    }

    /**
     * 应用主题。必须在 super.onCreate 之前调用。
     *
     * <p>若上次开启 MD3 时启动失败（boot guard 未被清除），
     * 本次自动降级为兼容主题，避免用户永远进不去。</p>
     */
    private void applyTheme() {
        android.content.SharedPreferences sp =
                getSharedPreferences("hyvq_filemgr", MODE_PRIVATE);

        boolean md3Off = sp.getBoolean(PREF_MD3_OFF, false);
        // 上次启动没走到 markBootOk → 说明 MD3 主题在这台机器上起不来，自动降级
        boolean lastBootFailed = sp.getBoolean(PREF_MD3_BOOT_GUARD, false);
        if (!md3Off && lastBootFailed) {
            md3Off = true;
            sp.edit().putBoolean(PREF_MD3_OFF, true).apply();
        }

        int theme = Math.max(0, Math.min(1, sp.getInt(PREF_THEME, 0)));
        int mode = sp.getInt("ui_mode", 0);

        if (md3Off) {
            if (theme == 1) {
                // 工业风格本来就是深色，兼容模式用 Dark AppCompat 近似
                setTheme(R.style.Theme_HYFileManager_Compat_Dark);
            } else if (mode == 1) {
                setTheme(R.style.Theme_HYFileManager_Compat_Light);
            } else if (mode == 2) {
                setTheme(R.style.Theme_HYFileManager_Compat_Dark);
            } else {
                setTheme(R.style.Theme_HYFileManager_Compat);
            }
            return;
        }

        if (theme == 1) {
            setTheme(R.style.Theme_HYFileManager_Industrial);
        } else if (mode == 1) {
            setTheme(R.style.Theme_HYFileManager_Light);
        } else if (mode == 2) {
            setTheme(R.style.Theme_HYFileManager_Dark);
        }
        // 都没设 → 用 AndroidManifest 里的默认主题
    }

    /** 启动成功：清掉 boot guard */
    private void markBootOk() {
        getSharedPreferences("hyvq_filemgr", MODE_PRIVATE)
                .edit().putBoolean(PREF_MD3_BOOT_GUARD, false).apply();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        module = new FileManagerModule();
        module.setBgPickerLauncher(() -> bgPickerLauncher.launch(
                new androidx.activity.result.PickVisualMediaRequest.Builder()
                        .setMediaType(androidx.activity.result.contract
                                .ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
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
        // 背景图选图结果转发给模块（模块持有壁纸落盘与渲染逻辑）
        if (module != null) module.onPickBackgroundResult(requestCode, resultCode, data);
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
