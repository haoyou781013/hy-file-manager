package com.aliya.hyfilemgr.anime;

import android.content.Context;

/** 观看历史占位（HY文件管理 不记录番剧进度，所有方法空实现）。 */
public class WatchHistory {
    public WatchHistory(Context ctx) { }

    public void updateProgress(String key, long positionMs, long durationMs) { }

    public long get(String key) {
        return 0L;
    }
}
