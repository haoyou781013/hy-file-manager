package com.aliya.hyfilemgr.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link DebugMode#onTap} 的单元测试。
 *
 * <p><b>为什么这段逻辑值得测</b>：「点 7 次开调试模式」看着简单，
 * 但有三个容易写错的边界：超时重置、达成后归零、剩余次数计算。
 * 写错了的表现是「点不满 7 次就触发」或「点够了没反应」——
 * 都属于用户能直接感知但排查起来麻烦的问题。</p>
 *
 * <p>这也是把这段逻辑从 UI 的 onClick 里抽出来的原因：
 * 原来只能装机连点 7 次才能验证。</p>
 */
public class DebugModeTest {

    private static final long T = DebugMode.TAP_TIMEOUT_MS;

    @Test
    public void firstTap_startsAtOne() {
        DebugMode.TapResult r = DebugMode.onTap(0, 0L, 1000L);
        assertEquals(1, r.count);
        assertFalse(r.completed);
        assertEquals("还差 6 次", 6, r.remaining);
    }

    @Test
    public void consecutiveTaps_accumulate() {
        int count = 0;
        long last = 0L;
        long now = 1000L;
        for (int i = 1; i <= 5; i++) {
            DebugMode.TapResult r = DebugMode.onTap(count, last, now);
            count = r.count;
            last = r.lastTapMs;
            assertEquals("第 " + i + " 次后计数", i, count);
            assertFalse(r.completed);
            now += 200;   // 间隔 200ms，在超时范围内
        }
    }

    @Test
    public void seventhTap_completes() {
        int count = 0;
        long last = 0L;
        long now = 1000L;
        DebugMode.TapResult r = null;
        for (int i = 1; i <= 7; i++) {
            r = DebugMode.onTap(count, last, now);
            count = r.count;
            last = r.lastTapMs;
            now += 200;
        }
        assertTrue("第 7 次应达成", r.completed);
        assertEquals("达成后计数归零", 0, r.count);
        assertEquals("达成后剩余为 0", 0, r.remaining);
    }

    @Test
    public void timeout_resetsCount() {
        DebugMode.TapResult r1 = DebugMode.onTap(0, 0L, 1000L);
        DebugMode.TapResult r2 = DebugMode.onTap(r1.count, r1.lastTapMs, 1200L);
        DebugMode.TapResult r3 = DebugMode.onTap(r2.count, r2.lastTapMs, 1400L);
        assertEquals(3, r3.count);

        // 超时（>1500ms）→ 重置为 1
        long farFuture = 1400L + T + 1;
        DebugMode.TapResult r4 = DebugMode.onTap(r3.count, r3.lastTapMs, farFuture);
        assertEquals("超时应重置为 1", 1, r4.count);
        assertFalse(r4.completed);
    }

    @Test
    public void exactlyAtTimeout_doesNotReset() {
        // 恰好等于超时阈值：用 > 比较，不算超时
        DebugMode.TapResult r1 = DebugMode.onTap(0, 0L, 1000L);
        DebugMode.TapResult r2 = DebugMode.onTap(r1.count, r1.lastTapMs, 1000L + T);
        assertEquals("恰好 1500ms 不应重置", 2, r2.count);
    }

    @Test
    public void afterCompletion_nextCycleStartsFresh() {
        int count = 0;
        long last = 0L;
        long now = 1000L;
        DebugMode.TapResult r = null;
        for (int i = 1; i <= 7; i++) {
            r = DebugMode.onTap(count, last, now);
            count = r.count;
            last = r.lastTapMs;
            now += 200;
        }
        assertTrue(r.completed);

        // 紧接着再点：lastTapMs=0 → 走「首次」分支
        DebugMode.TapResult next = DebugMode.onTap(r.count, r.lastTapMs, now);
        assertEquals("新一轮从 1 开始", 1, next.count);
        assertFalse("不应连续触发", next.completed);
    }

    @Test
    public void remaining_decreasesCorrectly() {
        int[] expected = {6, 5, 4, 3, 2, 1};
        int count = 0;
        long last = 0L;
        long now = 1000L;
        for (int i = 0; i < 6; i++) {
            DebugMode.TapResult r = DebugMode.onTap(count, last, now);
            count = r.count;
            last = r.lastTapMs;
            assertEquals("第 " + (i + 1) + " 次后的剩余次数", expected[i], r.remaining);
            now += 200;
        }
    }

    @Test
    public void requiredTaps_isSeven() {
        // 锁住常量：Android 系统惯例是 7 次，改这个值属于行为变更
        assertEquals(7, DebugMode.REQUIRED_TAPS);
    }

    @Test
    public void firstSixTaps_neverComplete() {
        int count = 0;
        long last = 0L;
        long now = 1000L;
        for (int i = 1; i <= 6; i++) {
            DebugMode.TapResult r = DebugMode.onTap(count, last, now);
            assertFalse("第 " + i + " 次不应达成", r.completed);
            count = r.count;
            last = r.lastTapMs;
            now += 200;
        }
    }
}
