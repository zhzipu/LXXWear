package cn.wearbbs.music.lan;

import java.util.ArrayList;
import java.util.List;

/**
 * 曲库里的一个歌单分组，来自手机端清单的 playlists 字段。
 * <p>
 * 同一首歌可以出现在多个歌单里（手机端只存一份文件），所以这里存的是 id 列表，
 * 真正的 {@code Song} 对象在 {@link LanLibrary#loadSongs} 里按 id 取。
 */
public class LanPlaylist {

    /** 手机端的歌单标识（本地列表 id / 网易云歌单 id） */
    public final String id;
    public final String name;
    /** 该歌单的曲目 id，顺序即歌单顺序 */
    public final List<String> songIds = new ArrayList<>();

    public LanPlaylist(String id, String name) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
    }

    public int size() {
        return songIds.size();
    }
}
