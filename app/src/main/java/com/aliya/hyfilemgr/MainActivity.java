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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
