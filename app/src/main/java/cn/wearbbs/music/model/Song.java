package cn.wearbbs.music.model;

/**
 * 播放列表中的一首歌。
 * url 为可播放地址（网络或本地路径），lyric 为 LRC 文本。
 */
public class Song {

    /** 歌曲在来源端的标识，局域网同步时用来做缓存键。 */
    private String id;
    private String name;
    private String artist;
    private String coverUrl;
    private String url;
    private String lyric;

    public Song() {
    }

    public Song(String name, String artist, String url) {
        this.name = name;
        this.artist = artist;
        this.url = url;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getLyric() {
        return lyric;
    }

    public void setLyric(String lyric) {
        this.lyric = lyric;
    }
}
