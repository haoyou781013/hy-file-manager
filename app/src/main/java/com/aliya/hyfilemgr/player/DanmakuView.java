package com.aliya.hyfilemgr.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 基础弹幕引擎（纯自绘，无第三方依赖）。
 *
 * <h3>范围（基础版）</h3>
 * 本类只负责**渲染与节流**：滚动弹幕、防重叠分轨、开关与时间轴对齐。
 * 弹幕数据由 {@link DanmakuProvider} 供给 —— 引擎不绑定任何远端源，
 * 后续接入弹弹play / B站等只需实现一个 Provider。
 *
 * <h3>实现要点</h3>
 * <ul>
 *   <li><b>分轨防重叠</b>：把屏幕高度切成固定条带，每条轨道记录"最后一个弹幕的进入时刻"，
 *       新弹幕只在**轨道空闲**时投放（前一条已滑出足够距离），否则顺延到下一轨；
 *       所有轨都忙时丢弃该弹幕（宁缺勿乱，避免糊屏）。</li>
 *   <li><b>时间轴</b>：以 {@code currentTimeMs} 为准，拖动/快进后由播放器回写，
 *       引擎只投递"落在当前时刻"的弹幕，不自行累加。</li>
 *   <li><b>性能</b>：无弹幕时直接跳过绘制；对象复用池避免频繁分配。</li>
 * </ul>
 */
public class DanmakuView extends View {

    /** 单条弹幕 */
    public static class Danmaku {
        public final String text;
        public final long timeMs;      // 出现时刻（相对片长 ms）
        public int color = Color.WHITE;
        public int sizeSp = 14;        // 字号
        /** 1=滚动(默认) 5=顶部 6=底部 —— 对齐 B站 弹幕协议 */
        public int mode = 1;

        // 运行态（由引擎维护）
        long appearAt = -1;            // 首次进入屏幕的墙钟时间
        float startX;
        int lane = -1;

        public Danmaku(String text, long timeMs) {
            this.text = text == null ? "" : text;
            this.timeMs = timeMs;
        }
    }

    /** 数据源接口：后续接弹弹play/B站只需实现它 */
    public interface DanmakuProvider {
        List<Danmaku> load(String episodeKey);
    }

    private final List<Danmaku> all = new ArrayList<>();      // 全量
    private final List<Danmaku> active = new ArrayList<>();   // 屏幕上
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long currentTimeMs = 0;
    private boolean playing = false;
    private boolean enabled = true;
    private int scanIdx = 0;           // 已投放到的位置（配合时间轴单向推进）

    // 轨道：laneLastAt[i] = 该轨最后一条弹幕的入场墙钟 ms
    private long[] laneLastAt;
    private static final int MIN_LANE_GAP_MS = 900;   // 同轨最小间隔
    private static final int LANES = 5;

    public DanmakuView(Context context) {
        this(context, null);
    }

    /**
     * XML inflate 专用构造。
     *
     * <p><b>崩溃根因</b>：{@code activity_media_player.xml} 里写了
     * {@code <com.aliya.hyfilemgr.player.DanmakuView>}，而本类只有单参构造 ——
     * {@code LayoutInflater} 必须用 {@code (Context, AttributeSet)} 反射实例化，
     * 找不到即抛 {@code NoSuchMethodException} → 播放页直接闪退。</p>
     */
    public DanmakuView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(4, 1, 1, 0x80000000);
        laneLastAt = new long[LANES];
        setWillNotDraw(false);
    }

    // ══════════════ 对外 API ══════════════

    /** 载入某集弹幕 */
    public void setDanmaku(List<Danmaku> list) {
        all.clear();
        active.clear();
        if (list != null) all.addAll(list);
        scanIdx = 0;
        for (int i = 0; i < laneLastAt.length; i++) laneLastAt[i] = 0;
        invalidate();
    }

    /** 播放器回写当前进度（拖动/快进后需重建投放状态） */
    public void syncTime(long positionMs, boolean isPlaying) {
        boolean seeked = Math.abs(positionMs - currentTimeMs) > 1500;
        currentTimeMs = positionMs;
        playing = isPlaying;
        if (seeked) {
            active.clear();
            for (int i = 0; i < laneLastAt.length; i++) laneLastAt[i] = 0;
            // 重新定位投放游标
            scanIdx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).timeMs > positionMs) break;
                scanIdx = i + 1;
            }
        }
    }

    public void setEnabled(boolean on) {
        if (this.enabled != on) {
            this.enabled = on;
            if (!on) active.clear();
            invalidate();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int count() {
        return all.size();
    }

    public void clear() {
        all.clear();
        active.clear();
        scanIdx = 0;
        invalidate();
    }

    // ══════════════ 投放 ══════════════

    /** 把时间轴上经过的弹幕投入屏幕（最多补投若干条，避免长时间后卡顿） */
    private void dispatch(long now) {
        if (!playing || !enabled) return;
        int budget = 8;
        while (scanIdx < all.size() && budget-- > 0) {
            Danmaku d = all.get(scanIdx);
            if (d.timeMs > currentTimeMs + 50) break;   // 未来弹幕等下一帧
            scanIdx++;
            // 已经过期太久（跳过 5 秒以上）不补投
            if (currentTimeMs - d.timeMs > 5000) continue;
            int lane = 1;   // 非滚动弹幕不占轨道，用占位值
            if (d.mode == 1) {
                lane = pickLane(now);
                if (lane < 0) continue;                 // 所有轨都忙 → 丢弃
            } else {
                // 顶部/底部去重：同屏已有同 mode 就丢弃
                boolean dup = false;
                for (Danmaku a : active) if (a.mode == d.mode) { dup = true; break; }
                if (dup) continue;
            }
            d.lane = lane;
            d.appearAt = now;
            d.startX = getWidth() + 10f;   // 从屏幕右侧外进入
            active.add(d);
        }
    }

    /** 选一条空闲轨道；全忙返回 -1 */
    private int pickLane(long now) {
        for (int i = 0; i < laneLastAt.length; i++) {
            if (now - laneLastAt[i] >= MIN_LANE_GAP_MS) {
                laneLastAt[i] = now;
                return i;
            }
        }
        return -1;
    }

    // ══════════════ 绘制 ══════════════

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled || getWidth() == 0 || getHeight() == 0) return;
        long now = System.currentTimeMillis();
        dispatch(now);

        float laneH = getHeight() / (float) LANES;
        float w = getWidth();
        float speed = w / 7000f;      // 7 秒穿过屏幕

        Iterator<Danmaku> it = active.iterator();
        while (it.hasNext()) {
            Danmaku d = it.next();
            float elapsed = now - d.appearAt;
            float x = d.startX - speed * elapsed;
            float textW = paint.measureText(d.text);
            if (x + textW < -50) {          // 完全滑出
                it.remove();
                continue;
            }
            paint.setColor(d.color);
            float tf = d.sizeSp * getResources().getDisplayMetrics().scaledDensity;
            paint.setTextSize(tf);
            float y;
            if (d.mode == 5) {
                // 顶部：居中固定，显示 4 秒后消失
                if (elapsed > 4000) { it.remove(); continue; }
                float tw = paint.measureText(d.text);
                x = (w - tw) / 2f;
                y = tf * 1.6f;
            } else if (d.mode == 6) {
                // 底部：居中固定，显示 4 秒后消失
                if (elapsed > 4000) { it.remove(); continue; }
                float tw = paint.measureText(d.text);
                x = (w - tw) / 2f;
                y = getHeight() - tf * 0.6f;
            } else {
                y = d.lane * laneH + laneH * 0.72f;
            }
            canvas.drawText(d.text, x, y, paint);
        }
        if (!active.isEmpty() || playing) {
            postInvalidateDelayed(33);      // ~30fps
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postInvalidateDelayed(33);
    }

}
