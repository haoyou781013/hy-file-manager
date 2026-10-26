package com.aliya.hyfilemgr;

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        module = new FileManagerModule();
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

    @Override
    protected void onDestroy() {
        if (module != null) module.onDetach();
        super.onDestroy();
    }
}
