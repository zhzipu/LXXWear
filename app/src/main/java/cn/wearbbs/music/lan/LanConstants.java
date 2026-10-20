package cn.wearbbs.music.lan;

/**
 * 局域网互联协议约定（v1）。
 * 手机端作为 HTTP 服务端，手表端作为客户端；手机同时通过 UDP 广播自己的地址。
 */
public final class LanConstants {

    /** 手机 HTTP 服务端口。 */
    public static final int DEFAULT_HTTP_PORT = 8686;
    /** UDP 发现端口，手机会定时往该端口广播。 */
    public static final int DISCOVERY_PORT = 8687;
    /** 广播包里的服务标识，防止误连其它程序。 */
    public static final String SERVICE_NAME = "lxnx-lan";
    public static final int PROTOCOL_VERSION = 1;

    public static final long DISCOVERY_TIMEOUT_MS = 5000L;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 15000;
    /** 等手机把某首歌准备好（没有本地文件时它要先下载）的最长时间。 */
    public static final long PREPARE_TIMEOUT_MS = 120000L;

    public static final String PATH_PING = "/api/ping";
    public static final String PATH_PLAYLIST = "/api/playlist";
    public static final String PATH_AUDIO = "/api/audio/";
    public static final String PATH_LYRIC = "/api/lyric/";
    public static final String PATH_COVER = "/api/cover/";
    public static final String PATH_PREPARE = "/api/prepare/";
    /** 告诉手机「这首手表本地已有，不用准备」 */
    public static final String PATH_SKIP = "/api/skip/";
    /** 一轮同步结束，带上统计 */
    public static final String PATH_FINISH = "/api/finish";

    private LanConstants() {
    }
}
