package com.aliya.hyfilemgr;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * HY 文件管理 · 入口 Activity
 *
 * <p>当前为骨架版本，仅显示欢迎文本。
 * 后续替换为双栏文件浏览（左目录树 + 右文件列表）。</p>
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText("HY 文件管理 v0.1.0-dev\n\n初始化骨架，功能开发中。");
        tv.setTextSize(18);
        tv.setPadding(48, 120, 48, 48);
        setContentView(tv);
    }
}
