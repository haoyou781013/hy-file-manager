package com.aliya.hyfilemgr;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 双列窗格的文件列表适配器。
 *
 * <p>单行条目：图标 + 名称 + 副标题（大小 / 时间）。目录优先已在 MainActivity 排好序。</p>
 */
public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.VH> {

    /** 点击回调：由窗格持有者处理导航 */
    public interface OnEntryClick {
        void onClick(File f);
    }

    private final Context ctx;
    private final List<File> data;
    private final OnEntryClick cb;
    private final int rowH;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public FileListAdapter(Context ctx, List<File> data, OnEntryClick cb) {
        this.ctx = ctx;
        this.data = data;
        this.cb = cb;
        this.rowH = Math.round(46 * ctx.getResources().getDisplayMetrics().density);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(8);
        row.setPadding(p, 0, p, 0);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        // 行背景（按下反馈用 selectableItemBackground）
        row.setBackgroundResource(com.aliya.hyfilemgr.R.drawable.bg_ripple);

        // 图标（纯色圆角方块，避免依赖 drawable 资源）
        ImageView icon = new ImageView(ctx);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
        ilp.setMarginEnd(dp(8));
        row.addView(icon, ilp);

        // 文字区（名称 + 副标题）
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvName = new TextView(ctx);
        tvName.setTextSize(13);
        tvName.setSingleLine(true);
        tvName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textCol.addView(tvName);

        TextView tvSub = new TextView(ctx);
        tvSub.setTextSize(10);
        tvSub.setSingleLine(true);
        textCol.addView(tvSub);

        return new VH(row, icon, tvName, tvSub);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final File f = data.get(position);
        boolean isDir = f.isDirectory();

        h.name.setText(f.getName().isEmpty() ? "/" : f.getName());
        h.name.setTextColor(isDir
                ? Color.parseColor("#1E5FA8")
                : Color.parseColor("#26323D"));

        // 副标题：目录显示子项数 + 时间；文件显示大小 + 时间
        String sub;
        if (isDir) {
            File[] kids = f.listFiles();
            int n = kids == null ? 0 : kids.length;
            sub = n + " 项  ·  " + fmt.format(new Date(f.lastModified()));
        } else {
            sub = humanSize(f.length()) + "  ·  " + fmt.format(new Date(f.lastModified()));
        }
        h.sub.setText(sub);
        h.sub.setTextColor(Color.parseColor("#7A8A99"));

        // 图标：目录用蓝色方块，文件用灰色
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(6));
        g.setColor(isDir ? Color.parseColor("#BFDCFA") : Color.parseColor("#E4EAF0"));
        h.icon.setBackground(g);
        h.icon.setImageResource(isDir ? R.drawable.ic_folder : R.drawable.ic_file);

        h.itemView.setOnClickListener(v -> {
            if (cb != null) cb.onClick(f);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /** 人类可读大小 */
    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.getDefault(), "%.0f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.getDefault(), "%.1f MB", m);
        return String.format(Locale.getDefault(), "%.2f GB", m / 1024.0);
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView sub;

        VH(View item, ImageView icon, TextView name, TextView sub) {
            super(item);
            this.icon = icon;
            this.name = name;
            this.sub = sub;
        }
    }
}
