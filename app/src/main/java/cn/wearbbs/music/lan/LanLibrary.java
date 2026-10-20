package cn.wearbbs.music.lan;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import cn.wearbbs.music.model.Song;

/**
 * 手表本地曲库：手机传过来的音频、封面、歌词都落在这里，索引存 index.json。
 * 目录在应用私有空间，不需要存储权限，卸载应用即清理。
 * <p>
 * index.json 结构：
 * {"playlistId":"...","playlistName":"...","revision":"...","syncedAt":0,"songs":[...]}
 */
public final class LanLibrary {

    private static final String TAG = "LanLibrary";
    private static final String DIR_NAME = "lan_library";
    private static final String INDEX_FILE = "index.json";

    /** 认得出来的音频扩展名，用于「这个文件是不是这首歌的音频」的判断 */
    private static final String[] AUDIO_EXTS = {
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "ape", "wma"
    };

    private LanLibrary() {
    }

    public static File dir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "创建曲库目录失败: " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static File audioFile(Context context, String id, String ext) {
        String suffix = TextUtils.isEmpty(ext) ? "mp3" : ext.replace(".", "");
        return new File(dir(context), sanitize(id) + "." + suffix);
    }

    /**
     * 找这首歌已经在本地落盘的音频，找到就说明「同步过了」，不用再传一遍。
     * <p>
     * 先按手机端这次给的扩展名找；找不到再按 id 前缀找其它扩展名 ——
     * 手机端可能因为音质回退、封装变化把 ext 改了，但文件还是同一首歌的完整音频，
     * 这种情况直接复用旧文件，不能让用户白等一轮传输。
     * <p>
     * {@code expectSize > 0} 时会校验文件长度，长度对不上（半截文件、被别的进程改过）
     * 才当作没同步过，避免把残缺文件当成成品。
     *
     * @return 可用的音频文件，没有则返回 null
     */
    public static File findCachedAudio(Context context, String id, String ext, long expectSize) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        File exact = audioFile(context, id, ext);
        if (isUsableAudio(exact, expectSize)) {
            return exact;
        }
        File[] files = dir(context).listFiles();
        if (files == null) {
            return null;
        }
        String prefix = sanitize(id) + ".";
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(prefix) || name.endsWith(".part")) {
                continue;
            }
            // 封面和音频共用同一个 id 前缀（<id>.jpg / <id>.mp3），
            // 这里只认音频扩展名，别把封面当成已同步的音频
            if (!isAudioName(name)) {
                continue;
            }
            if (isUsableAudio(file, expectSize)) {
                return file;
            }
        }
        return null;
    }

    /** 文件名是否是音频（按扩展名判断，忽略大小写） */
    private static boolean isAudioName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        for (String audio : AUDIO_EXTS) {
            if (audio.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUsableAudio(File file, long expectSize) {
        if (file == null || !file.isFile()) {
            return false;
        }
        long length = file.length();
        // 0 字节的残留不能当成已同步
        if (length <= 0) {
            return false;
        }
        return expectSize <= 0 || length == expectSize;
    }

    public static File coverFile(Context context, String id) {
        return new File(dir(context), sanitize(id) + ".jpg");
    }

    /**
     * 读取索引对象，不存在时返回空对象。兼容早期「顶层就是数组」的格式。
     */
    public static JSONObject readIndex(Context context) {
        File index = new File(dir(context), INDEX_FILE);
        if (!index.exists()) {
            return new JSONObject();
        }
        try {
            String text = readText(index).trim();
            if (text.startsWith("[")) {
                JSONObject legacy = new JSONObject();
                legacy.put("songs", new JSONArray(text));
                return legacy;
            }
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "读取曲库索引失败: " + e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * 读取本地曲库，音频文件缺失的条目会被跳过。
     */
    public static List<Song> load(Context context) {
        List<Song> songs = new ArrayList<>();
        JSONArray array = readIndex(context).optJSONArray("songs");
        if (array == null) {
            return songs;
        }
        for (int i = 0; i < array.length(); i++) {
            Song song = toSong(context, array.optJSONObject(i));
            if (song != null) {
                songs.add(song);
            }
        }
        return songs;
    }

    /**
     * 读取曲库里的歌单分组（手机端清单的 playlists 字段）。
     * <p>
     * 老版本索引没有这个字段，就把整份曲库当成一个歌单返回，界面不至于空着；
     * 一首都没同步过时返回空列表。
     */
    public static List<LanPlaylist> readPlaylists(Context context) {
        List<LanPlaylist> result = new ArrayList<>();
        JSONObject index = readIndex(context);
        JSONArray array = index.optJSONArray("playlists");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id");
                String name = item.optString("name");
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
                    continue;
                }
                LanPlaylist playlist = new LanPlaylist(id, name);
                JSONArray ids = item.optJSONArray("songIds");
                if (ids != null) {
                    for (int j = 0; j < ids.length(); j++) {
                        String songId = ids.optString(j);
                        if (!TextUtils.isEmpty(songId)) {
                            playlist.songIds.add(songId);
                        }
                    }
                }
                // 勾选了但一首歌都没选的歌单也要留着（界面上显示「0 首」），
                // 手机端勾选了哪些歌单，手表左侧就应该是哪些歌单。
                result.add(playlist);
            }
        }
        if (result.isEmpty()) {
            List<Song> songs = load(context);
            if (!songs.isEmpty()) {
                LanPlaylist legacy = new LanPlaylist(
                        index.optString("playlistId", "lan-all"),
                        index.optString("playlistName", "手机歌单"));
                for (Song song : songs) {
                    legacy.songIds.add(song.getId());
                }
                result.add(legacy);
            }
        }
        return result;
    }

    /**
     * 按歌单分组取出曲目：顺序跟歌单一致，音频缺失（被清理或还没下完）的歌自动跳过。
     */
    public static List<Song> loadSongs(Context context, LanPlaylist playlist) {
        List<Song> result = new ArrayList<>();
        if (playlist == null || playlist.songIds.isEmpty()) {
            return result;
        }
        Map<String, Song> all = new HashMap<>();
        for (Song song : load(context)) {
            all.put(song.getId(), song);
        }
        for (String id : playlist.songIds) {
            Song song = all.get(id);
            if (song != null) {
                result.add(song);
            }
        }
        return result;
    }

    public static Song toSong(Context context, JSONObject entry) {        if (entry == null) {
            return null;
        }
        String audioName = entry.optString("audio");
        if (TextUtils.isEmpty(audioName)) {
            return null;
        }
        File audio = new File(dir(context), audioName);
        if (!audio.exists()) {
            return null;
        }
        Song song = new Song();
        song.setId(entry.optString("id"));
        song.setName(entry.optString("name"));
        song.setArtist(entry.optString("artist"));
        song.setUrl(audio.getAbsolutePath());
        song.setLyric(entry.optString("lyric"));

        String coverName = entry.optString("cover");
        if (!TextUtils.isEmpty(coverName)) {
            File cover = new File(dir(context), coverName);
            if (cover.exists()) {
                song.setCoverUrl(cover.getAbsolutePath());
            }
        }
        return song;
    }

    public static void saveIndex(Context context, JSONObject index) {
        File file = new File(dir(context), INDEX_FILE);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(index.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "写入曲库索引失败: " + e.getMessage());
        }
    }

    /**
     * 按清单做一次彻底清理：目录里只要不属于 keepIds 的文件（音频、封面、断点残留）全部删掉。
     * <p>
     * 和 {@link #deleteSongFiles} 的区别：这个方法是从「应该留下什么」出发扫全目录，
     * 所以哪怕索引里没有记录的孤儿文件（比如上次同步中途被杀掉的 .part）也会被收拾掉。
     * 手机上取消勾选某个歌单后重新同步，那个歌单的歌就是这样从手表上消失的。
     *
     * @return 删掉的文件个数
     */
    public static int prune(Context context, Set<String> keepIds) {
        Set<String> keepStems = new HashSet<>();
        if (keepIds != null) {
            for (String id : keepIds) {
                if (!TextUtils.isEmpty(id)) {
                    keepStems.add(sanitize(id));
                }
            }
        }
        File[] files = dir(context).listFiles();
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File file : files) {
            String name = file.getName();
            if (INDEX_FILE.equals(name) || !file.isFile()) {
                continue;
            }
            // 断点残留（id.mp3.part）在同步收尾时已经没有意义，直接清掉
            if (name.endsWith(".part")) {
                if (file.delete()) {
                    removed++;
                }
                continue;
            }
            // id 经过 sanitize 后不含 '.'，所以第一个 '.' 之前就是曲目 id
            int dot = name.indexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            if (!keepStems.contains(stem)) {
                if (file.delete()) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            Log.i(TAG, "清理曲库：删除 " + removed + " 个文件，保留 " + keepStems.size() + " 首");
        }
        return removed;
    }

    /**
     * 同步开始时的「先删」：把不在本次清单里的歌（手机端取消勾选的歌单、歌单里删掉的歌）
     * 的文件立刻清掉，歌单页重建后马上看不到它们，不用等整轮同步跑完。
     * <p>
     * 和 {@link #prune} 的区别：这里不碰索引，也保留清单里还有的歌的 {@code .part} 断点，
     * 下次同步能接着传。
     *
     * @return 删掉的文件个数
     */
    public static int removeMissing(Context context, Set<String> keepIds) {
        Set<String> keepStems = new HashSet<>();
        if (keepIds != null) {
            for (String id : keepIds) {
                if (!TextUtils.isEmpty(id)) {
                    keepStems.add(sanitize(id));
                }
            }
        }
        File[] files = dir(context).listFiles();
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File file : files) {
            String name = file.getName();
            if (INDEX_FILE.equals(name) || !file.isFile()) {
                continue;
            }
            // id 经 sanitize 后不含 '.'，所以第一个 '.' 之前就是曲目 id（.part 也适用）
            int dot = name.indexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            if (!keepStems.contains(stem) && file.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            Log.i(TAG, "同步前清理：删除 " + removed + " 个不在本次清单里的文件");
        }
        return removed;
    }

    /**
     * 删掉某首歌在曲库里的所有文件（音频、封面等），用于歌单里已移除的歌。
     */
    public static void deleteSongFiles(Context context, String id) {
        if (TextUtils.isEmpty(id)) {
            return;
        }
        String prefix = sanitize(id) + ".";
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().startsWith(prefix)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static String readText(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
            return out.toString("UTF-8");
        }
    }

    /** 返回目录占用的字节数。 */
    public static long sizeOnDisk(Context context) {
        long total = 0;
        File[] files = dir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                total += file.length();
            }
        }
        return total;
    }

    public static void clear(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static String sanitize(String id) {
        if (TextUtils.isEmpty(id)) {
            return "unknown";
        }
        String cleaned = id.replaceAll("[^A-Za-z0-9_-]", "_");
        if (!cleaned.equals(id)) {
            // 含非 ASCII 字符时补一段哈希：否则「测试歌手 - 测试歌曲」和「别的歌手 - 别的歌曲」
            // 会被清洗成同一个文件名，互相覆盖
            cleaned = cleaned + "-" + shortHash(id);
        }
        return cleaned;
    }

    private static String shortHash(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
