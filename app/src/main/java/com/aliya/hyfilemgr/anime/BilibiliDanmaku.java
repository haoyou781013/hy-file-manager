package com.aliya.hyfilemgr.anime;

import java.util.List;

/** B 站弹幕抓取占位（HY文件管理 不含网络弹幕，恒返回空列表）。 */
public final class BilibiliDanmaku {
    private BilibiliDanmaku() { }

    public static List<com.aliya.hyfilemgr.player.DanmakuView.Danmaku> fetch(
            long seasonId, int episodeNo) throws Exception {
        return java.util.Collections.emptyList();
    }
}
