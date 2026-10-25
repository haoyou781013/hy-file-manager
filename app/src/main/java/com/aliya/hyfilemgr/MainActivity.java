package com.aliya.hyfilemgr;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * HY 文件管理 · 主界面（双列）
 *
 * <p>参考 HY_VQ 的 FileManagerModule 双栏设计：左右两个独立窗格各占 1/2 宽度，
 * 中间 1dp 分隔线。进入即为文件管理，无首页/无抽屉。</p>
 *
 * <p>窗格职责（对齐 MT 管理器）：</p>
 * <ul>
 *   <li>左窗格：起始为存储根 /storage/emulated/0</li>
 *   <li>右窗格：同样起始为存储根，可独立导航</li>
 *   <li>点目录 = 进入；点文件 = 暂无动作（后续接打开器）</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    /** 运行时权限请求码（Android 11 以下需要 READ/WRITE_EXTERNAL_STORAGE） */
    private static final int REQ_STORAGE = 1001;

    /** 存储根目录 */
    private static final String ROOT = Environment.getExternalStorageDirectory().getAbsolutePath();

    /** 单个窗格：路径 + 条目 + 视图 + 适配器 */
    private static final class Pane {
        String path;
        final List<File> entries = new ArrayList<>();
        RecyclerView list;
        FileListAdapter adapter;
        TextView pathBar;

        Pane(String path) {
            this.path = path;
        }
    }

    private final Pane paneL = new Pane(ROOT);
    private final Pane paneR = new Pane(ROOT);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_window));

        // ── 顶部标题栏 ──
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // ── 中部双列：左窗格 + 分隔线 + 右窗格（同 HY_VQ buildContent）──
        root.addView(buildContent(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // 首次进入：请求存储权限后加载
        ensurePermissionThenLoad();
    }

    /** 顶部标题栏：应用名 + 活动窗格路径 */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_toolbar));
        bar.setPadding(dp(16), 0, dp(12), 0);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(16);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return bar;
    }

    /** 中部双列：左窗格 | 1dp 分隔线 | 右窗格 */
    private View buildContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);

        content.addView(buildPane(paneL), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
        content.addView(divider, new LinearLayout.LayoutParams(
                dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildPane(paneR), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        return content;
    }

    /** 构建单窗格：路径栏 + RecyclerView */
    private View buildPane(final Pane p) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        // 窗格路径栏
        p.pathBar = new TextView(this);
        p.pathBar.setTextSize(11);
        p.pathBar.setSingleLine(true);
        p.pathBar.setEllipsize(android.text.TextUtils.TruncateAt.START);
        p.pathBar.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        p.pathBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_pathbar));
        p.pathBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        p.pathBar.setText(p.path);
        wrap.addView(p.pathBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 文件列表
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        p.list = rv;
        final Pane self = p;
        p.adapter = new FileListAdapter(this, p.entries, f -> onEntryClick(self, f));
        rv.setAdapter(p.adapter);
        wrap.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 初始建视图时先渲染一次（此时可能还没权限，列表为空壳）
        load(p);
        return wrap;
    }

    /** 条目点击：目录进入 / 文件暂不处理 */
    private void onEntryClick(Pane owner, File f) {
        if (f.isDirectory()) {
            owner.path = f.getAbsolutePath();
            load(owner);
        }
        // 文件：后续接「打开方式」，当前版本忽略
    }

    /** 若尚未授权则请求，授权后加载两个窗格 */
    private void ensurePermissionThenLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：用「所有文件访问权限」，否则只能读媒体目录
            if (!Environment.isExternalStorageManager()) {
                // 没有该权限也能列出部分目录，先尝试加载，权限由用户手动开启
                loadBoth();
                return;
            }
        } else {
            String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_STORAGE);
                loadBoth();   // 先加载可读部分
                return;
            }
        }
        loadBoth();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            loadBoth();
        }
    }

    /** 加载左右两个窗格 */
    private void loadBoth() {
        load(paneL);
        load(paneR);
    }

    /** 读取目录内容并刷新该窗格 */
    private void load(final Pane p) {
        if (p == null) return;
        p.entries.clear();
        File dir = new File(p.path);
        File[] files = dir.listFiles();
        if (files != null) {
            p.entries.addAll(Arrays.asList(files));
        }
        // 排序：目录优先，然后按名称（忽略大小写）
        p.entries.sort(new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                boolean da = a.isDirectory(), db = b.isDirectory();
                if (da != db) return da ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        if (p.pathBar != null) p.pathBar.setText(p.path);
        if (p.adapter != null) p.adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
