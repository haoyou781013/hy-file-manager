package com.aliya.hyfilemgr.anime;

/**
 * 番剧源配置占位（HY文件管理 不含番剧功能）。
 *
 * <p>保留类型与 {@link #parse(String)} 签名，供 MediaPlayerActivity 编过；
 * 恒返回 null，调用处会走「缺少源配置」分支——即文件管理器场景下番剧切集不可用。</p>
 */
public class AnimeSource {

    public String name;

    public static AnimeSource parse(String json) {
        return null;
    }

    /** 单集 */
    public static class Episode {
        public final String id;
        public final String title;
        public final String url;

        public Episode(String id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = url;
        }
    }

    /** 解析出的可播放链接 */
    public static class PlayLink {
        public final String url;
        public final java.util.Map<String, String> headers;

        public PlayLink(String url, java.util.Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }
}
