package com.aliya.hyfilemgr;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.aliya.hyfilemgr.access.FileEntry;
import com.aliya.hyfilemgr.filemgr.FileOps;

import java.util.List;

/**
 * 双列窗格的条目适配器（套用 HY_VQ FileListAdapter 的行结构）。
 *
 * <p>行 = [复选框(多选时)] [类型图标] [名称 / 副标题]，支持普通点击与长按进多选。</p>
 */
public class FileEntryAdapter extends RecyclerView.Adapter<FileEntryAdapter.VH> {

    public interface Listener {
        void onClick(FileEntry e);
        void onLongClick(FileEntry e);
    }

    private final Context ctx;
    private final List<FileEntry> data;
    private final Listener cb;
    private final LinearLayout opBar;
    private final MainActivity host;
    private final int rowH;

    public FileEntryAdapter(Context ctx, List<FileEntry> data, Listener cb,
                            LinearLayout opBar, Object paneTag) {
        this.ctx = ctx;
        this.data = data;
        this.cb = cb;
        this.opBar = opBar;
        this.host = (MainActivity) ctx;
        this.rowH = Math.round(50 * ctx.getResources().getDisplayMetrics().density);
        if (opBar != null && opBar.getChildCount() == 0) {
            opBar.addView(btn("复制", "复制"));
            opBar.addView(btn("移动", "移动"));
            opBar.addView(btn("删除", "删除"));
            opBar.addView(btn("重命名", "重命名"));
        }
    }

    private TextView btn(String label, final String op) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(Color.parseColor("#1E5FA8"));
        int p = dp(9);
        tv.setPadding(p, p, p, p);
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        tv.setOnClickListener(v -> host.onOp(currentPane(), op));
        return tv;
    }

    private MainActivity.Pane currentPane() {
        return host.activePane();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = dp(8);
        row.setPadding(ph, 0, ph, 0);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        row.setBackgroundResource(android.R.drawable.list_selector_background);

        CheckBox cb = new CheckBox(ctx);
        cb.setClickable(false);
        cb.setFocusable(false);
        cb.setVisibility(View.GONE);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cblp.setMarginEnd(dp(2));
        row.addView(cb, cblp);

        ImageView icon = new ImageView(ctx);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(26), dp(26));
        ilp.setMarginEnd(dp(8));
        row.addView(icon, ilp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setTextSize(12.5f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        col.addView(name);

        TextView sub = new TextView(ctx);
        sub.setTextSize(9.5f);
        col.addView(sub);

        return new VH(row, cb, icon, name, sub);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final FileEntry e = data.get(position);

        h.name.setText(e.parent ? ".." : e.name);
        h.name.setTextColor(Color.parseColor(e.isDir() ? "#1E5FA8" : "#26323D"));

        String sub;
        if (e.parent) {
            sub = "返回上级";
        } else if (e.isDir()) {
            sub = FileOps.formatDate(e.lastModified);
        } else {
            sub = FileOps.formatSize(e.length) + "  ·  " + FileOps.formatDate(e.lastModified);
        }
        h.sub.setText(sub);
        h.sub.setTextColor(Color.parseColor("#7A8A99"));

        // 图标
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(5));
        g.setColor(e.isDir() ? Color.parseColor("#BFDCFA") : Color.parseColor("#E4EAF0"));
        h.icon.setBackground(g);
        h.icon.setImageResource(e.isDir() ? R.drawable.ic_folder : R.drawable.ic_file);

        // 多选态
        MainActivity.Pane p = currentPane();
        boolean multi = p != null && p.multiMode;
        h.cb.setVisibility(multi && !e.parent ? View.VISIBLE : View.GONE);
        h.cb.setChecked(p != null && p.selected.contains(e));

        h.itemView.setOnClickListener(v -> { if (cb != null) cb.onClick(e); });
        h.itemView.setOnLongClickListener(v -> { if (cb != null) cb.onLongClick(e); return true; });
    }

    @Override
    public int getItemCount() { return data.size(); }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static class VH extends RecyclerView.ViewHolder {
        final CheckBox cb;
        final ImageView icon;
        final TextView name;
        final TextView sub;

        VH(View item, CheckBox cb, ImageView icon, TextView name, TextView sub) {
            super(item);
            this.cb = cb;
            this.icon = icon;
            this.name = name;
            this.sub = sub;
        }
    }
}
