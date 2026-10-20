package cn.wearbbs.music.player;

import java.io.Serializable;

import cn.wearbbs.music.model.Song;

/**
 * 交给播放器服务的最小歌曲信息。
 * <p>
 * 不带歌词：100 首的歌词拼起来能上百 KB，而 Intent 走 binder 有 1MB 上限，
 * 一旦撑爆就是「连不上播放器」这种很难查的故障。
 * 歌词只有界面需要，界面自己从 {@link PlaylistProvider} 取。
 */
public final class PlaybackItem implements Serializable {

    private static final long serialVersionUID = 1L;

    public final String id;
    public final String name;
    public final String artist;
    public final String cover;
    public final String url;

    public PlaybackItem(String id, String name, String artist, String cover, String url) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.cover = cover;
        this.url = url;
    }

    public static PlaybackItem from(Song song) {
        if (song == null) {
            return null;
        }
        return new PlaybackItem(song.getId(), song.getName(), song.getArtist(),
                song.getCoverUrl(), song.getUrl());
    }
}
