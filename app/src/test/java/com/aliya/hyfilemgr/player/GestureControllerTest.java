package com.aliya.hyfilemgr.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link GestureController} 的纯函数部分测试。
 *
 * <p><b>为什么不测整个控制器</b>：{@code GestureController} 构造函数需要
 * {@code Context} 和 {@code GestureDetector}，JVM 上跑不了。所以把三个
 * 核心判定提成 {@code public static} 纯函数单独测——这三个恰好是最容易出错、
 * 且用户最容易察觉的部分。</p>
 *
 * <p>跑法：{@code gradle testReleaseUnitTest}</p>
 */
public class GestureControllerTest {

    // ══════════ zoneOf：双击分区（调研结论：左/中/右各 1/3）══════════

    @Test
    public void zoneOf_leftThirdIsLeft() {
        // 宽度 900：0~300 是左区
        assertEquals(GestureSink.Zone.LEFT, GestureController.zoneOf(0, 900));
        assertEquals(GestureSink.Zone.LEFT, GestureController.zoneOf(150, 900));
        assertEquals(GestureSink.Zone.LEFT, GestureController.zoneOf(299, 900));
    }

    @Test
    public void zoneOf_middleThirdIsCenter() {
        assertEquals(GestureSink.Zone.CENTER, GestureController.zoneOf(300, 900));
        assertEquals(GestureSink.Zone.CENTER, GestureController.zoneOf(450, 900));
        assertEquals(GestureSink.Zone.CENTER, GestureController.zoneOf(600, 900));
    }

    @Test
    public void zoneOf_rightThirdIsRight() {
        assertEquals(GestureSink.Zone.RIGHT, GestureController.zoneOf(601, 900));
        assertEquals(GestureSink.Zone.RIGHT, GestureController.zoneOf(750, 900));
        assertEquals(GestureSink.Zone.RIGHT, GestureController.zoneOf(900, 900));
    }

    @Test
    public void zoneOf_boundaryIsExact() {
        // 恰好 1/3 与 2/3 处：左边界归 CENTER（因为条件是 r < 1/3），
        // 右边界归 CENTER（因为条件是 r > 2/3）。
        // 这条测试锁住语义：边界值不能同时属于两个分区，也不能漏。
        int w = 900;
        assertEquals("恰好 300 属于 CENTER", GestureSink.Zone.CENTER,
                GestureController.zoneOf(300f, w));
        assertEquals("恰好 600 属于 CENTER", GestureSink.Zone.CENTER,
                GestureController.zoneOf(600f, w));
    }

    @Test
    public void zoneOf_unknownWidthFallsBackToCenter() {
        // 宽度未知（layout 还没完成）时宁可暂停也不乱跳时间——
        // 跳错时间用户会以为播放器坏了，暂停最多点错一次
        assertEquals(GestureSink.Zone.CENTER, GestureController.zoneOf(0, 0));
        assertEquals(GestureSink.Zone.CENTER, GestureController.zoneOf(500, 0));
        assertEquals(GestureSink.Zone.CENTER, GestureController.zoneOf(500, -1));
    }

    // ══════════ decideSlideAxis：滑动方向判定 ══════════

    @Test
    public void decideSlideAxis_belowThresholdReturnsNull() {
        // 小幅抖动不该被判成滑动
        assertNull(GestureController.decideSlideAxis(5, 5, 12));
        assertNull(GestureController.decideSlideAxis(-5, 5, 12));
        assertNull(GestureController.decideSlideAxis(12, 12, 12));  // 恰好等于阈值不算
    }

    @Test
    public void decideSlideAxis_horizontalWins() {
        // 横位移更大 → 横向
        assertEquals(Boolean.TRUE, GestureController.decideSlideAxis(30, 10, 12));
        assertEquals(Boolean.TRUE, GestureController.decideSlideAxis(-30, 10, 12));
        assertEquals(Boolean.TRUE, GestureController.decideSlideAxis(30, -10, 12));
    }

    @Test
    public void decideSlideAxis_verticalWins() {
        assertEquals(Boolean.FALSE, GestureController.decideSlideAxis(10, 30, 12));
        assertEquals(Boolean.FALSE, GestureController.decideSlideAxis(10, -30, 12));
    }

    @Test
    public void decideSlideAxis_usesMaxAbsNotTotal() {
        // 旧实现如果用「总位移 sqrt(dx²+dy²) > 阈值」，那么 (10,10) 会被判成滑动
        // （总位移 14.1 > 12），但它其实是斜向的小抖动。
        // 新实现按主轴判断：(10,10) 两个方向都没超 12 → 不算滑动。
        assertNull("斜向小抖动不该判成滑动", GestureController.decideSlideAxis(10, 10, 12));
    }

    // ══════════ isInEdgeGuard：边缘保护区 ══════════

    @Test
    public void isInEdgeGuard_leftEdge() {
        // 宽度 1000，保护区 16px：x<16 算保护区
        assertTrue(GestureController.isInEdgeGuard(0, 1000, 16));
        assertTrue(GestureController.isInEdgeGuard(15, 1000, 16));
        assertFalse(GestureController.isInEdgeGuard(16, 1000, 16));
        assertFalse(GestureController.isInEdgeGuard(500, 1000, 16));
    }

    @Test
    public void isInEdgeGuard_rightEdge() {
        // x > 1000-16 = 984 算保护区
        assertFalse(GestureController.isInEdgeGuard(984, 1000, 16));
        assertTrue(GestureController.isInEdgeGuard(985, 1000, 16));
        assertTrue(GestureController.isInEdgeGuard(1000, 1000, 16));
    }

    @Test
    public void isInEdgeGuard_unknownWidthDoesNotGuard() {
        // 宽度未知时不限制——总比把手势全屏蔽掉好
        assertFalse(GestureController.isInEdgeGuard(5, 0, 16));
        assertFalse(GestureController.isInEdgeGuard(5, -1, 16));
    }

    @Test
    public void isInEdgeGuard_narrowView() {
        // 极窄 View（宽度 < 2*guard）时整个区域都算保护区。
        // 实际不会出现（播放器至少 320dp），但不能算出负数区间。
        assertTrue(GestureController.isInEdgeGuard(0, 20, 16));
        assertTrue(GestureController.isInEdgeGuard(19, 20, 16));
    }

    // ══════════ clampBubbleX：拖动时间气泡定位 ══════════

    @Test
    public void clampBubbleX_centersOnFinger() {
        // 手指在正中间，气泡应居中对齐：600 - 100/2 = 550
        assertEquals(550f, GestureController.clampBubbleX(600, 100, 1200, 8), 0.01f);
    }

    @Test
    public void clampBubbleX_clampsAtLeftEdge() {
        // 手指在最左（x=0），气泡会算出 -50 → 必须夹到 margin
        assertEquals(8f, GestureController.clampBubbleX(0, 100, 1200, 8), 0.01f);
        // 手指稍往右但仍会溢出
        assertEquals(8f, GestureController.clampBubbleX(30, 100, 1200, 8), 0.01f);
    }

    @Test
    public void clampBubbleX_clampsAtRightEdge() {
        // 手指在最右（x=1200），算出 1150，但右边要留 margin：
        // maxLeft = 1200 - 100 - 8 = 1092
        assertEquals(1092f, GestureController.clampBubbleX(1200, 100, 1200, 8), 0.01f);
    }

    @Test
    public void clampBubbleX_bubbleWiderThanContainer() {
        // 气泡比容器还宽（极窄屏 / 长文本）：贴左边，不能算出负的 maxLeft
        float r = GestureController.clampBubbleX(100, 500, 300, 8);
        assertEquals("气泡过宽时贴 margin", 8f, r, 0.01f);
    }

    @Test
    public void clampBubbleX_unknownContainerWidth() {
        // 容器宽度未知（首次 layout 前）：返回 0，不崩
        assertEquals(0f, GestureController.clampBubbleX(100, 100, 0, 8), 0.01f);
        assertEquals(0f, GestureController.clampBubbleX(100, 100, -1, 8), 0.01f);
    }

    // ══════════ 组合场景 ══════════

    @Test
    public void edgeGuard_and_zoneAreIndependent() {
        // 左边缘同时是 LEFT 区且是保护区：
        // - 双击应该判为 LEFT（快退）
        // - 但横向滑动不该生效（让给系统返回手势）
        // 两个判定互不干扰，这点必须保证。
        int w = 1000;
        assertEquals(GestureSink.Zone.LEFT, GestureController.zoneOf(8, w));
        assertTrue(GestureController.isInEdgeGuard(8, w, 16));
    }
}
