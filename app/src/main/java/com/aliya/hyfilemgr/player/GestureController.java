package com.aliya.hyfilemgr.player;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 手势识别器：把 {@link MotionEvent} 翻译成 {@link GestureSink} 上的语义回调。
 *
 * <p><b>职责边界</b>：只识别，不执行。不知道 player、不知道 HUD、不改任何 View。
 * 唯一的内部状态是自己的手势判定过程（是否已判定方向、起始坐标等）。</p>
 *
 * <p><b>手势映射</b>（对齐主流播放器，调研见 PLAYER-REDESIGN-V2.md）：</p>
 * <table>
 *   <tr><th>手势</th><th>行为</th></tr>
 *   <tr><td>单击</td><td>显示/隐藏控制条</td></tr>
 *   <tr><td>双击左侧 1/3</td><td>快退 10s</td></tr>
 *   <tr><td>双击中间 1/3</td><td>播放/暂停</td></tr>
 *   <tr><td>双击右侧 1/3</td><td>快进 10s</td></tr>
 *   <tr><td>长按</td><td>临时加速（2×），松手恢复</td></tr>
 *   <tr><td>横向滑动</td><td>seek：拖动中预览，抬手提交</td></tr>
 *   <tr><td>纵向滑动（左半）</td><td>亮度</td></tr>
 *   <tr><td>纵向滑动（右半）</td><td>音量</td></tr>
 * </table>
 *
 * <p><b>为什么双击要分区</b>：调研确认 YouTube / B 站都是「双击左 1/3 后退、
 * 右 1/3 前进、中间才暂停」。旧实现是「双击任意处暂停」——与用户肌肉记忆不符，
 * 且损失了双击 seek 这个高频操作。保留中间区域暂停是为了不破坏已有习惯。</p>
 *
 * <p><b>为什么长按要先结束再判滑动</b>：长按 2× 与横向 seek 同时生效会让
 * 「拖动预览的时间」与「实际播放速度」不一致，且用户在长按后滑动时，
 * 意图已经从「加速」变成「跳转」。所以一旦判定为滑动，立刻发出
 * {@link GestureSink#onLongPressEnd()}。</p>
 */
public final class GestureController {

    /** 判定为滑动的阈值（dp）：小于此位移视为点击抖动 */
    private static final int SLIDE_THRESHOLD_DP = 12;

    /** 屏幕边缘禁用横向 seek 的宽度（dp）。避让系统手势导航。 */
    private static final int EDGE_GUARD_DP = 16;

    /** 双击分区：左/右各占 1/3，中间留给暂停 */
    private static final float ZONE_SPLIT = 1f / 3f;

    private final Context context;
    private final GestureSink sink;
    private final GestureDetector detector;
    private final int thresholdPx;
    private final int edgeGuardPx;

    // ── 手势判定状态（仅本类使用）──
    private float downX, downY;
    private boolean horizontalMode, verticalMode;
    /** 滑动起点是否落在边缘保护区（此时不识别横向 seek） */
    private boolean startedInEdgeGuard;
    private boolean longPressing;

    public GestureController(@NonNull Context context, @NonNull GestureSink sink) {
        this.context = context;
        this.sink = sink;
        float density = context.getResources().getDisplayMetrics().density;
        this.thresholdPx = (int) (SLIDE_THRESHOLD_DP * density);
        this.edgeGuardPx = (int) (EDGE_GUARD_DP * density);

        this.detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                sink.onSurfaceTap();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                sink.onDoubleTap(zoneOf(e.getX(), lastWidth));
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                // 从边缘起手的滑动交给系统手势，不触发长按加速
                if (startedInEdgeGuard) return;
                if (longPressing) return;      // 幂等保护
                longPressing = true;
                sink.onLongPressStart();
            }

            @Override public boolean onDown(MotionEvent e) {
                downX = e.getX();
                downY = e.getY();
                horizontalMode = verticalMode = false;
                // ⚠ 边缘判定必须在这里做，不能依赖 onTouchEvent 的 ACTION_DOWN 分支：
                // GestureDetector.onDown 与本类的 ACTION_DOWN 分支谁先执行没有保证，
                // 且 onLongPress 回调可能在 ACTION_DOWN 分支之前就触发。
                // （旧实现只有一条路径，所以不存在这个时序问题；
                //  拆成控制器后事件源变多，必须自洽。）
                startedInEdgeGuard = isInEdgeGuard(e.getX(), lastWidth, edgeGuardPx);
                return true;
            }
        });
    }

    /** 最近一次 {@link #attach} 时的 View 宽度，用于双击分区（避免回调里拿不到 View） */
    private int lastWidth = 0;
    /** 最近一次 attach 的 View 高度 */
    private int lastHeight = 0;

    /**
     * 绑定到某个 View 上。
     *
     * <p>替代原有的 {@code root.setOnTouchListener(...)} 一长串 lambda。
     * 注意这里**不用** {@code setOnTouchListener}（会与其它 touch 处理互斥），
     * 而是靠调用方在 {@code onTouchEvent} 里转发——见 {@link #onTouchEvent}。</p>
     */
    public void attach(@NonNull View view) {
        lastWidth = view.getWidth();
        lastHeight = view.getHeight();
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            lastWidth = r - l;
            lastHeight = b - t;
        });
    }

    /**
     * 转发触摸事件。调用方应在自己的 {@code onTouchEvent} / {@code setOnTouchListener} 里调用。
     *
     * @return true 表示本控制器消费了该事件
     */
    public boolean onTouchEvent(@NonNull MotionEvent ev, int viewWidth, int viewHeight) {
        if (viewWidth > 0) lastWidth = viewWidth;
        if (viewHeight > 0) lastHeight = viewHeight;

        final int action = ev.getActionMasked();

        // 越界保护：手指移出 View 后系统不再派发后续事件，ACTION_UP 会丢，
        // 导致长按加速永远退不出来（这是已上报过的 bug 的成因之一）。
        if (action == MotionEvent.ACTION_MOVE && longPressing) {
            if (ev.getX() < 0 || ev.getY() < 0
                    || ev.getX() > viewWidth || ev.getY() > viewHeight) {
                endLongPress();
            }
        }

        detector.onTouchEvent(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                // 边缘判定已在 GestureDetector.onDown 里做（见那里的注释）。
                // 这里只兜底：若 onDown 还没被调用（某些 ROM 的事件顺序不同），补一次。
                if (isInEdgeGuard(ev.getX(), viewWidth, edgeGuardPx)) {
                    startedInEdgeGuard = true;
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;

                if (!horizontalMode && !verticalMode) {
                    Boolean horiz = decideSlideAxis(dx, dy, thresholdPx);
                    if (horiz != null) {
                        horizontalMode = horiz;
                        verticalMode = !horiz;
                        // 判定为滑动 → 长按意图已被取代，立刻结束加速
                        if (longPressing) endLongPress();
                    }
                }

                if (horizontalMode) {
                    if (startedInEdgeGuard) return true;   // 边缘保护区不 seek
                    float ratio = dx / Math.max(1f, viewWidth);
                    sink.onSeekPreview(ratio);
                } else if (verticalMode) {
                    float ratio = -dy / Math.max(1f, viewHeight);
                    sink.onVerticalSwipe(ratio, downX < viewWidth / 2f);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                endLongPress();
                if (horizontalMode && !startedInEdgeGuard) {
                    float ratio = (ev.getX() - downX) / Math.max(1f, viewWidth);
                    sink.onSeekCommit(ratio);
                }
                horizontalMode = verticalMode = false;
                startedInEdgeGuard = false;
                return true;
            }

            default:
                return false;
        }
    }

    /** 结束长按（幂等）。任何可能打断长按的路径都应调用它。 */
    private void endLongPress() {
        if (!longPressing) return;
        longPressing = false;
        sink.onLongPressEnd();
    }

    /** 外部主动结束长按（如 onPause / 切集 / 锁定）。 */
    public void cancelLongPress() {
        endLongPress();
    }

    /** 是否正处于长按加速中 */
    public boolean isLongPressing() {
        return longPressing;
    }

    /**
     * 按横坐标判定双击分区。
     *
     * <p>宽度未知（0）时返回 CENTER——宁可暂停也不乱跳时间
     * （跳错时间用户会以为播放器坏了，暂停最多是点错一次）。</p>
     *
     * <p>{@code public static} 是为了能单测：这是双击行为的核心判定，
     * 边界（1/3 与 2/3 处）必须精确。</p>
     */
    @NonNull
    public static GestureSink.Zone zoneOf(float x, int width) {
        if (width <= 0) return GestureSink.Zone.CENTER;
        // ⚠ 必须用整数比较，不能用 `x / width > 2f/3f`。
        //
        // 曾经写成浮点：600/900 = 0.6666667f，而 1f - 1f/3f = 0.6666666f
        // → 0.6666667 > 0.6666666 成立 → 恰好落在 2/3 边界的点被误判为 RIGHT。
        // 单测 zoneOf_boundaryIsExact 抓到了这个问题。
        //
        // 整数比较：x * 3 < width  → 左区；x * 3 > width * 2 → 右区；其余中间。
        // 避免除法产生的精度损失，边界点归属明确。
        final float x3 = x * 3f;
        if (x3 < width) return GestureSink.Zone.LEFT;
        if (x3 > width * 2f) return GestureSink.Zone.RIGHT;
        return GestureSink.Zone.CENTER;
    }

    /**
     * 是否应该判定为滑动（而非点击抖动）。
     *
     * <p>用「主轴位移超过阈值」判断，而不是「总位移」。用总位移会让
     * 斜向小幅抖动也被判成滑动。</p>
     *
     * @param dx      横位移（px）
     * @param dy      纵位移（px）
     * @param thresh  阈值（px）
     * @return null 表示未达阈值；否则返回是否为横向（true=横向）
     */
    @Nullable
    public static Boolean decideSlideAxis(float dx, float dy, int thresh) {
        if (Math.abs(dx) <= thresh && Math.abs(dy) <= thresh) return null;
        return Math.abs(dx) > Math.abs(dy);
    }

    /**
     * 起点是否落在边缘保护区（该区域让给系统手势，不识别横向 seek）。
     *
     * @param x     起点横坐标
     * @param width View 宽度；<=0 时视为不在保护区（宽度未知时不限制）
     * @param guard 保护区宽度（px）
     */
    public static boolean isInEdgeGuard(float x, int width, int guard) {
        if (width <= 0) return false;
        return x < guard || x > width - guard;
    }
}
