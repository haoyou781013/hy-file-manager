package com.aliya.hyfilemgr.player;

/**
 * 手势的语义出口。
 *
 * <p><b>为什么要这层接口</b>：{@link GestureController} 只负责「把触摸事件翻译成
 * 用户意图」，不负责执行——它不知道 player 存在，也不知道 HUD 长什么样。</p>
 *
 * <p>这样拆开后有两项实际收益：</p>
 * <ol>
 *   <li><b>可单测</b>：喂一串 {@code MotionEvent}，断言产生了哪些事件。
 *       重构前要验证「双击左侧是否快退」，必须装机点屏幕。</li>
 *   <li><b>换皮不动逻辑</b>：HUD 改成侧边条、seek 改成 30 秒一跳、
 *       亮度改用系统亮度滑块——都只改实现，手势识别一行不动。</li>
 * </ol>
 *
 * <p><b>参数为什么是「比例」而不是「绝对值」</b>：亮度/音量是 0..1 的比例，
 * 由实现方决定映射到系统哪个范围。手势层不关心具体档位。</p>
 */
public interface GestureSink {

    // ── 点击类 ──

    /** 单击画面（显示/隐藏控制条） */
    void onSurfaceTap();

    /**
     * 双击。
     *
     * @param zone  {@link Zone}，由手势层根据点击位置判定
     */
    void onDoubleTap(Zone zone);

    /** 长按开始（通常映射为临时加速） */
    void onLongPressStart();

    /** 长按结束（松手 / 被打断 / 滑动手势接管）——必须幂等 */
    void onLongPressEnd();

    // ── 滑动类 ──

    /**
     * 横向滑动中（预览，尚未提交）。
     *
     * @param deltaRatio 相对屏幕宽度的位移比例。正值=向右滑（通常映射为快进），
     *                   负值=向左滑。由实现方换算成秒数。
     */
    void onSeekPreview(float deltaRatio);

    /**
     * 横向滑动松手（提交）。
     *
     * @param deltaRatio 与最后一次 onSeekPreview 相同的比例
     */
    void onSeekCommit(float deltaRatio);

    /**
     * 纵向滑动（亮度/音量）。
     *
     * @param deltaRatio 相对屏幕高度的位移比例。正值=向上滑。
     * @param leftSide   true 表示从屏幕左半起始（通常映射为亮度），
     *                   false 为右半（音量）
     */
    void onVerticalSwipe(float deltaRatio, boolean leftSide);

    /**
     * 手势区判定（供 HUD 显示用）。
     */
    enum Zone {
        /** 左侧 1/3：双击后退 */
        LEFT,
        /** 中间 1/3：双击暂停/播放 */
        CENTER,
        /** 右侧 1/3：双击前进 */
        RIGHT
    }
}
