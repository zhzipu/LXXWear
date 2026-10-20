package cn.wearbbs.music.player;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.util.SharedPreferencesUtil;

/**
 * 播放列表数据源。
 * <p>
 * 默认读取 assets/playlist.json，也可以在任意位置调用 {@link #setSongs(List)} 注入自己的数据。
 * 播放列表只保存歌曲信息与当前索引，不做任何网络请求。
 */
public final class PlaylistProvider {

    private static final String TAG = "PlaylistProvider";
    private static final String ASSET_NAME = "playlist.json";

    /** 上次播到的那首歌（id 比序号稳，歌单前后补歌也能找回同一首）。 */
    private static final String KEY_LAST_ID = "last_played_song_id";
    /** 歌单里已经找不到那首时的退路：上次的序号。 */
    private static final String KEY_LAST_INDEX = "last_played_index";

    /**
     * 播放列表整体换掉时（同步线程在后台不断往里加歌），这里用「先建新表再整体替换」的方式，
     * 界面线程读到的要么是旧表、要么是新表，不会撞上「刚 clear 还没 addAll」的空窗。
     */
    private static volatile List<Song> songs = new ArrayList<>();
    private static volatile int currentIndex = 0;

    private PlaylistProvider() {
    }

    /**
     * 从 assets/playlist.json 读取播放列表，格式为：
     * [{"name":"歌名","artist":"歌手","cover":"封面地址","url":"音频地址","lyric":"[00:00.00]歌词"}]
     */
    public static void loadFromAssets(Context context) {
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(ASSET_NAME), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 assets/" + ASSET_NAME + " 失败: " + e.getMessage());
            return;
        }

        try {
            JSONArray array = new JSONArray(json.toString());
            List<Song> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Song song = new Song();
                song.setName(item.optString("name"));
                song.setArtist(item.optString("artist"));
                song.setCoverUrl(item.optString("cover"));
                song.setUrl(item.optString("url"));
                song.setLyric(item.optString("lyric"));
                if (!TextUtils.isEmpty(song.getUrl())) {
                    list.add(song);
                }
            }
            setSongs(list);
        } catch (Exception e) {
            Log.w(TAG, "解析 assets/" + ASSET_NAME + " 失败: " + e.getMessage());
        }
    }

    /**
     * 替换整个播放列表，索引回到第一首。
     */
    public static void setSongs(List<Song> newSongs) {
        replaceSongs(newSongs, null);
    }

    /**
     * 替换播放列表，并尽量把当前索引保持在 keepSongId 这首歌上（歌单被手机改过时用）。
     */
    public static void replaceSongs(List<Song> newSongs, String keepSongId) {
        List<Song> next = new ArrayList<>();
        if (newSongs != null) {
            next.addAll(newSongs);
        }
        int index = 0;
        if (keepSongId != null) {
            for (int i = 0; i < next.size(); i++) {
                if (keepSongId.equals(next.get(i).getId())) {
                    index = i;
                    break;
                }
            }
        }
        songs = next;
        currentIndex = index;
    }

    public static List<Song> getSongs() {
        return Collections.unmodifiableList(songs);
    }

    public static boolean isEmpty() {
        return songs.isEmpty();
    }

    public static int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 设置当前索引，越界会自动取模，列表为空时固定为 0。
     */
    public static void setCurrentIndex(int index) {
        if (songs.isEmpty()) {
            currentIndex = 0;
            return;
        }
        currentIndex = ((index % songs.size()) + songs.size()) % songs.size();
    }

    public static Song getCurrent() {
        return songs.isEmpty() ? null : songs.get(currentIndex);
    }

    /**
     * 交给播放器服务的整张表（不含歌词，见 {@link PlaybackItem}）。
     */
    public static PlaybackItem[] getPlaybackItems() {
        List<Song> snapshot = songs;
        PlaybackItem[] items = new PlaybackItem[snapshot.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = PlaybackItem.from(snapshot.get(i));
        }
        return items;
    }

    /** 某首歌在列表里的位置，找不到返回 -1。 */
    public static int indexOfId(String id) {
        if (TextUtils.isEmpty(id)) {
            return -1;
        }
        List<Song> snapshot = songs;
        for (int i = 0; i < snapshot.size(); i++) {
            if (id.equals(snapshot.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 播放器服务切歌后回填当前索引（自动连播、系统控件切歌都会走到这里）。
     * <p>
     * 优先按歌曲 id 定位：同步任务随时可能整表替换，序号会漂，id 不会。
     * 顺带把「上次播到哪」落到本地，下次启动接着这首。
     */
    public static void syncCurrentIndex(int index, String songId) {
        int byId = indexOfId(songId);
        setCurrentIndex(byId >= 0 ? byId : index);
        rememberCurrent();
    }

    /** 记下当前这首歌，下次启动从它开始。 */
    public static void rememberCurrent() {
        Song song = getCurrent();
        if (song == null) {
            return;
        }
        SharedPreferencesUtil.putString(KEY_LAST_ID, song.getId());
        SharedPreferencesUtil.putInt(KEY_LAST_INDEX, currentIndex);
    }

    /**
     * 启动时恢复上次退出时正在播的那首，让它成为这次的「首曲」。
     * 歌单里已经没有这首了（被手机删掉）就退回上次的序号。
     */
    public static void restoreLastPlayed() {
        if (songs.isEmpty()) {
            return;
        }
        int byId = indexOfId(SharedPreferencesUtil.getString(KEY_LAST_ID, null));
        if (byId >= 0) {
            currentIndex = byId;
            return;
        }
        setCurrentIndex(SharedPreferencesUtil.getInt(KEY_LAST_INDEX, 0));
    }
}
