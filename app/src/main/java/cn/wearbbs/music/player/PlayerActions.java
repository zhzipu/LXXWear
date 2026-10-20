package cn.wearbbs.music.player;

/**
 * 各界面之间的本地广播约定，只在本进程内使用。
 */
public final class PlayerActions {

    /** 播放进度，EXTRA_POSITION 为毫秒。 */
    public static final String ACTION_POSITION = "cn.wearbbs.music.action.POSITION";
    /** 播放列表里点了某一首，EXTRA_INDEX 为目标索引。 */
    public static final String ACTION_SELECT_INDEX = "cn.wearbbs.music.action.SELECT_INDEX";
    /** 当前歌曲已切换。 */
    public static final String ACTION_SONG_CHANGED = "cn.wearbbs.music.action.SONG_CHANGED";
    /** 播放列表被局域网同步替换了。 */
    public static final String ACTION_PLAYLIST_CHANGED = "cn.wearbbs.music.action.PLAYLIST_CHANGED";
    /**
     * 曲库的歌单结构变了，但播放列表没动（比如同步刚开始、歌单已建好但一首歌都还没落盘）。
     * 只有歌单页需要刷新，播放器页不该因此清空正在放的东西。
     */
    public static final String ACTION_LIBRARY_CHANGED = "cn.wearbbs.music.action.LIBRARY_CHANGED";
    /** 请求切回播放器页。 */
    public static final String ACTION_SHOW_PLAYER = "cn.wearbbs.music.action.SHOW_PLAYER";
    /** 播放顺序换档了：EXTRA_TEXT 是要在时间栏上闪一下的模式名。 */
    public static final String ACTION_ORDER_CHANGED = "cn.wearbbs.music.action.ORDER_CHANGED";

    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_TEXT = "text";
    /** 当前这首歌的 id，列表被同步换过时靠它把索引找回正确的歌。 */
    public static final String EXTRA_SONG_ID = "songId";

    private PlayerActions() {
    }
}
