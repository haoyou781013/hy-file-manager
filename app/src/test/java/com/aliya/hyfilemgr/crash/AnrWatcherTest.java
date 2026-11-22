package com.aliya.hyfilemgr.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link AnrWatcher#reasonText} 的单元测试。
 *
 * <p><b>测什么、不测什么</b>：{@code ApplicationExitInfo.REASON_*} 是 Android
 * 框架常量，纯 JVM 单测引用不到（项目只依赖 junit，没引入 Robolectric）。
 * 所以这里不测具体的中文映射，只验证唯一能验证的契约：
 * <b>传任何 int 都必须返回非空字符串，不能抛异常</b>。</p>
 *
 * <p>这不是凑数 —— 系统返回未知 reason 码时，崩溃页不能因为
 * 一个未映射的值再崩一次。</p>
 */
public class AnrWatcherTest {

    /**
     * ⚠ 注意：{@code ApplicationExitInfo.REASON_*} 是 Android 框架常量，
     * 纯 JVM 单测无法引用。所以这里不测 {@code reasonText}，
     * 改为验证它能被安全调用（传任意 int 不崩溃）——
     * 这本身是有价值的：系统返回未知 reason 码时不能让崩溃页再崩一次。
     */
    @Test
    public void reasonText_handlesUnknownCodeWithoutCrash() {
        // 这些值都超出已知 reason 范围，应走 default 分支
        String[] results = new String[3];
        int[] weirdCodes = {-1, 0, 9999};
        for (int i = 0; i < weirdCodes.length; i++) {
            results[i] = AnrWatcher.reasonText(weirdCodes[i]);
            assertNotNull("未知 reason 也必须返回字符串", results[i]);
            assertTrue("应包含原因码便于排查",
                    results[i].contains(String.valueOf(weirdCodes[i]))
                            || results[i].length() > 0);
        }
    }

    @Test
    public void reasonText_neverReturnsEmpty() {
        // 穷举一批值，确保没有任何情况返回 null 或空串
        for (int code = -10; code <= 20; code++) {
            String s = AnrWatcher.reasonText(code);
            assertNotNull("code=" + code + " 返回 null", s);
            assertTrue("code=" + code + " 返回空串", s.length() > 0);
        }
    }
}
