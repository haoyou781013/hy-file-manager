package com.aliya.hyfilemgr;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aliya.hyfilemgr.access.FileEntry;
import com.aliya.hyfilemgr.filemgr.FileOps;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HY 文件管理 · 主界面（双列）
 *
 * <p>套用 HY_VQ 的文件管理基础能力（参考 FileManagerModule）：</p>
 * <ul>
 *   <li>双窗格：左 | 1dp 分隔 | 右，各 weight=1</li>
 *   <li>基础操作：进入目录 / 返回上级 / 多选 / 复制 / 移动 / 删除 / 重命名 / 新建</li>
 * </ul>
 * <p>未含（后续再加）：ZIP 浏览、SAF、root、打开器、搜索。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 1001;
    private static final String ROOT = Environment.getExternalStorageDirectory().getAbsolutePath();

    /** 单窗格 */
    public static final class Pane {
        String path;
        final List<FileEntry> entries = new ArrayList<>();
        final Set<FileEntry> selected = new LinkedHashSet<>();
        RecyclerView list;
        FileEntryAdapter adapter;
        TextView pathBar;
        boolean multiMode;
        LinearLayout opBar;

        Pane(String path) { this.path = path; }
    }

    /** 适配器读取当前活动窗格（多选态判定用） */
    public Pane activePane() { return active; }

    private final Pane paneL = new Pane(ROOT);
    private final Pane paneR = new Pane(ROOT);
    private Pane active = paneL;

    /** 剪贴板：待复制/移动的路径 + 是否为移动 */
    private final List<File> clip = new ArrayList<>();
    private boolean clipMove = false;
    private LinearLayout clipBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_window));

        root.addView(buildTopBar(), lp(52));
        clipBar = buildClipBar();
        root.addView(clipBar, lp(ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(buildContent(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        ensurePermissionThenLoad();
    }

    private LinearLayout.LayoutParams lp(int h) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
    }

    /** 顶栏：应用名 + 当前活动窗格路径 + 新建菜单 */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_toolbar));
        bar.setPadding(dp(14), 0, dp(6), 0);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(15);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.getPaint().setFakeBoldText(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnNew = textBtn("新建");
        btnNew.setOnClickListener(v -> showNewMenu());
        bar.addView(btnNew);
        return bar;
    }

    /** 剪贴板栏：有内容时出现 */
    private LinearLayout buildClipBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_pathbar));
        bar.setPadding(dp(12), dp(6), dp(6), dp(6));
        bar.setVisibility(View.GONE);

        TextView info = new TextView(this);
        info.setTextSize(11);
        info.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        bar.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnPaste = textBtn("粘贴");
        btnPaste.setOnClickListener(v -> doPaste());
        bar.addView(btnPaste);

        TextView btnClear = textBtn("清空");
        btnClear.setOnClickListener(v -> {
            clip.clear(); refreshClipBar();
        });
        bar.addView(btnClear);
        return bar;
    }

    private void refreshClipBar() {
        if (clipBar == null) return;
        if (clip.isEmpty()) {
            clipBar.setVisibility(View.GONE);
            return;
        }
        clipBar.setVisibility(View.VISIBLE);
        TextView info = (TextView) ((LinearLayout) clipBar).getChildAt(0);
        info.setText((clipMove ? "移动 " : "复制 ") + clip.size() + " 项 → 选目标窗格粘贴");
    }

    /** 双列容器 */
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

    /** 单窗格：路径栏 + 操作栏 + 列表 */
    private View buildPane(final Pane p) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        p.pathBar = new TextView(this);
        p.pathBar.setTextSize(10);
        p.pathBar.setSingleLine(true);
        p.pathBar.setEllipsize(android.text.TextUtils.TruncateAt.START);
        p.pathBar.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        p.pathBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_pathbar));
        p.pathBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        p.pathBar.setText(p.path);
        p.pathBar.setOnClickListener(v -> {
            active = p;
            showPathMenu(p);
        });
        wrap.addView(p.pathBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 操作栏（多选时出现）
        p.opBar = new LinearLayout(this);
        p.opBar.setOrientation(LinearLayout.HORIZONTAL);
        p.opBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_pathbar));
        p.opBar.setVisibility(View.GONE);
        wrap.addView(p.opBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        p.list = rv;
        p.adapter = new FileEntryAdapter(this, p.entries, new FileEntryAdapter.Listener() {
            @Override
            public void onClick(FileEntry e) {
                active = p;
                if (p.multiMode) {
                    toggleSelect(p, e);
                } else {
                    onEntry(p, e);
                }
            }

            @Override
            public void onLongClick(FileEntry e) {
                active = p;
                if (!p.multiMode) {
                    p.multiMode = true;
                    p.selected.clear();
                    p.pathBar.setText("已选 " + 0);
                    if (p.opBar != null) p.opBar.setVisibility(View.VISIBLE);
                }
                toggleSelect(p, e);
            }
        }, p.opBar, p);
        rv.setAdapter(p.adapter);
        wrap.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        load(p);
        return wrap;
    }

    /** 点击条目：目录进入，文件提示 */
    private void onEntry(Pane p, FileEntry e) {
        if (e.parent) {
            goUp(p);
            return;
        }
        if (e.isDir()) {
            p.path = e.path;
            load(p);
        } else {
            toast(e.name + "\n" + FileOps.formatSize(e.length));
        }
    }

    /** 返回上级 */
    private void goUp(Pane p) {
        File cur = new File(p.path);
        File parent = cur.getParentFile();
        if (parent == null || !parent.canRead()) {
            p.path = ROOT;
        } else {
            p.path = parent.getAbsolutePath();
        }
        load(p);
    }

    /** 多选切换 */
    private void toggleSelect(Pane p, FileEntry e) {
        if (e.parent) return;
        if (p.selected.contains(e)) p.selected.remove(e);
        else p.selected.add(e);
        p.pathBar.setText("已选 " + p.selected.size());
        if (p.opBar != null) p.opBar.setVisibility(
                p.multiMode && !p.selected.isEmpty() ? View.VISIBLE : View.GONE);
        if (p.selected.isEmpty()) exitMulti(p);
        else p.adapter.notifyDataSetChanged();
    }

    private void exitMulti(Pane p) {
        p.multiMode = false;
        p.selected.clear();
        if (p.opBar != null) p.opBar.setVisibility(View.GONE);
        p.pathBar.setText(p.path);
        p.adapter.notifyDataSetChanged();
    }

    // ── 文件操作 ──

    private void showNewMenu() {
        final Pane p = active;
        new AlertDialog.Builder(this)
                .setTitle("新建")
                .setItems(new String[]{"文件夹", "文件"}, (d, which) -> {
                    EditText et = new EditText(this);
                    et.setHint(which == 0 ? "文件夹名" : "文件名");
                    et.setInputType(InputType.TYPE_CLASS_TEXT);
                    new AlertDialog.Builder(this)
                            .setTitle(which == 0 ? "新建文件夹" : "新建文件")
                            .setView(et)
                            .setPositiveButton("确定", (dd, ww) -> {
                                String name = et.getText().toString().trim();
                                if (name.isEmpty()) { toast("名称不能为空"); return; }
                                if (which == 0) {
                                    FileOps.mkdirAsync(new File(p.path), name,
                                            (ok, msg) -> { afterOp(p, ok, msg); });
                                } else {
                                    FileOps.createFileAsync(new File(p.path), name,
                                            (ok, msg) -> { afterOp(p, ok, msg); });
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .show();
    }

    private void showPathMenu(Pane p) {
        exitMulti(p);
        List<String> items = new ArrayList<>();
        items.add("返回上级");
        items.add("回到根目录");
        items.add("全选");
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) goUp(p);
                    else if (which == 1) { p.path = ROOT; load(p); }
                    else {
                        p.multiMode = true;
                        p.selected.clear();
                        for (FileEntry e : p.entries) if (!e.parent) p.selected.add(e);
                        p.pathBar.setText("已选 " + p.selected.size());
                        if (p.opBar != null) p.opBar.setVisibility(View.VISIBLE);
                        p.adapter.notifyDataSetChanged();
                    }
                })
                .show();
    }

    /** 多选操作：复制/移动/删除/重命名 */
    public void onOp(Pane p, String op) {
        List<FileEntry> sel = new ArrayList<>(p.selected);
        if (sel.isEmpty()) { toast("未选择"); return; }
        switch (op) {
            case "复制":
            case "移动":
                clip.clear();
                for (FileEntry e : sel) clip.add(new File(e.path));
                clipMove = "移动".equals(op);
                refreshClipBar();
                exitMulti(p);
                toast("已" + op + "到剪贴板，切到目标窗格点粘贴");
                break;
            case "删除":
                new AlertDialog.Builder(this)
                        .setTitle("删除 " + sel.size() + " 项？")
                        .setMessage("不可恢复")
                        .setPositiveButton("删除", (d, w) -> {
                            for (FileEntry e : sel) {
                                FileOps.deleteAsync(new File(e.path), (ok, msg) -> { });
                            }
                            exitMulti(p);
                            p.list.postDelayed(() -> load(p), 300);
                        })
                        .setNegativeButton("取消", null)
                        .show();
                break;
            case "重命名":
                if (sel.size() != 1) { toast("只能重命名单项"); return; }
                FileEntry e0 = sel.get(0);
                EditText et = new EditText(this);
                et.setText(e0.name);
                et.setInputType(InputType.TYPE_CLASS_TEXT);
                new AlertDialog.Builder(this)
                        .setTitle("重命名")
                        .setView(et)
                        .setPositiveButton("确定", (d, w) -> {
                            String nn = et.getText().toString().trim();
                            if (nn.isEmpty()) { toast("名称不能为空"); return; }
                            FileOps.renameAsync(new File(e0.path), nn,
                                    (ok, msg) -> afterOp(p, ok, msg));
                        })
                        .setNegativeButton("取消", null)
                        .show();
                break;
        }
    }

    /** 粘贴 */
    private void doPaste() {
        if (clip.isEmpty()) { toast("剪贴板为空"); return; }
        final Pane p = active;
        final File destDir = new File(p.path);
        final List<File> items = new ArrayList<>(clip);
        final boolean move = clipMove;
        final int[] done = {0};
        for (File f : items) {
            if (move) {
                FileOps.moveAsync(f, destDir, (ok, msg) -> {
                    if (++done[0] == items.size()) { clip.clear(); refreshClipBar(); load(p); }
                });
            } else {
                FileOps.copyAsync(f, destDir, (ok, msg) -> {
                    if (++done[0] == items.size()) { load(p); }
                });
            }
        }
    }

    private void afterOp(Pane p, boolean ok, String msg) {
        if (!ok) toast("失败：" + msg);
        load(p);
    }

    // ── 加载 ──

    private void ensurePermissionThenLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            loadBoth();
        } else {
            String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_STORAGE);
            }
            loadBoth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (rc == REQ_STORAGE) loadBoth();
    }

    private void loadBoth() {
        load(paneL);
        load(paneR);
    }

    /** 读取目录 → FileEntry 列表（含 ".." 返回上级） */
    private void load(final Pane p) {
        p.entries.clear();

        File dir = new File(p.path);
        if (!dir.exists() || !dir.canRead()) {
            p.path = ROOT;
            dir = new File(ROOT);
        }

        // ".." 置顶
        if (!ROOT.equals(p.path)) {
            p.entries.add(FileEntry.parent(new File(p.path).getParent()));
        }

        File[] files = dir.listFiles();
        if (files != null) {
            List<File> list = new ArrayList<>(Arrays.asList(files));
            list.sort(new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    boolean da = a.isDirectory(), db = b.isDirectory();
                    if (da != db) return da ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : list) {
                String name = f.getName();
                if (name.startsWith(".")) continue;   // 暂不显示隐藏
                p.entries.add(new FileEntry(
                        f.getAbsolutePath(), name,
                        f.isDirectory() ? FileEntry.Type.DIR : FileEntry.Type.FILE,
                        f.length(), f.lastModified(), false, null));
            }
        }

        p.pathBar.setText(p.path);
        if (p.adapter != null) p.adapter.notifyDataSetChanged();
    }

    // ── 小工具 ──

    private TextView textBtn(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(ContextCompat.getColor(this, R.color.blue_600));
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setClickable(true);
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        return tv;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
