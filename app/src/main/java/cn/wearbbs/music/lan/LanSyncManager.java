package cn.wearbbs.music.lan;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlaylistProvider;
import cn.wearbbs.music.service.MusicPlayerService;

/**
 * 与手机端对齐歌单：
 * 发现手机 → 读 /api/playlist → 版本没变就直接返回 → 否则逐首补齐（缺的下载、多余的删掉）→ 写索引、刷新播放列表。
 * <p>
 * {@link Mode#AUTO} 由 {@link LanAutoSync} 定时调用，只在手机端歌单变化时才真正传文件。
 */
public class LanSyncManager {

    private static final String TAG = "LanSyncManager";

    /** 并发下载的线程数，和手机端的并行准备路数对齐 */
    private static final int SYNC_PARALLEL = 5;

    /** 手机端歌词改过时重新取歌词的次数与间隔：它那边可能还在准备这一版 */
    private static final int LYRIC_FETCH_RETRY = 3;
    private static final long LYRIC_FETCH_RETRY_DELAY_MS = 700L;

    public enum Mode {
        /** 手动触发：即使版本相同也会校验一遍。 */
        FULL,
        /** 自动触发：版本没变直接跳过。 */
        AUTO
    }

    public interface Callback {
        /** 主状态 + 次要说明（可为空）。可能来自子线程。 */
        void onStatus(String status, String detail);

        /** 总体进度 0-100。 */
        void onProgress(int percent);

        /** 又一首歌落盘完成。 */
        void onSongReady(Song song, int finished, int total);

        void onFinished(boolean success, String message);
    }

    /** 同一时刻只允许一个同步任务。 */
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    /** 当前正在跑的那一个。同步属于整个进程，同步页关掉也不中断，这里给页面重新挂上来用。 */
    private static volatile LanSyncManager current;

    /** 是否有同步任务正在跑（常驻广播监听据此避免重复触发）。 */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** 正在跑的那个同步任务，没有则返回 null。 */
    public static LanSyncManager running() {
        LanSyncManager instance = current;
        return instance != null && instance.running ? instance : null;
    }

    private volatile boolean running;
    private volatile boolean cancelled;
    private LanDiscovery discovery;

    /**
     * 页面挂上来的回调。可以为 null（自动同步时没人看，或者用户已经退出同步页），
     * 所有进度都先记在下面的 last* 字段里，页面回来时 {@link #attach} 会补一遍。
     */
    private volatile Callback listener;
    private volatile String lastStatus = "";
    private volatile String lastDetail = "";
    private volatile int lastPercent;
    private volatile boolean finished;
    private volatile boolean finishedSuccess;
    private volatile String finishedMessage = "";

    public boolean isRunning() {
        return running;
    }

    /** 转发给当前挂着的页面；没有页面时只更新内部状态。 */
    private final Callback forward = new Callback() {
        @Override
        public void onStatus(String status, String detail) {
            lastStatus = status == null ? "" : status;
            lastDetail = detail == null ? "" : detail;
            Callback target = listener;
            if (target != null) {
                target.onStatus(lastStatus, lastDetail);
            }
        }

        @Override
        public void onProgress(int percent) {
            lastPercent = percent;
            Callback target = listener;
            if (target != null) {
                target.onProgress(percent);
            }
        }

        @Override
        public void onSongReady(Song song, int finished, int total) {
            Callback target = listener;
            if (target != null) {
                target.onSongReady(song, finished, total);
            }
        }

        @Override
        public void onFinished(boolean success, String message) {
            finished = true;
            finishedSuccess = success;
            finishedMessage = message == null ? "" : message;
            Callback target = listener;
            if (target != null) {
                target.onFinished(success, finishedMessage);
            }
        }
    };

    /**
     * 重新把界面挂到这个同步任务上：先把当前进度补一遍，之后实时收更新。
     * 用户从同步页退出去听歌、再回来时用这个，不会从头再传一次。
     */
    public void attach(Callback callback) {
        listener = callback;
        if (callback == null) {
            return;
        }
        if (!lastStatus.isEmpty()) {
            callback.onStatus(lastStatus, lastDetail);
        }
        callback.onProgress(lastPercent);
        if (finished) {
            callback.onFinished(finishedSuccess, finishedMessage);
        }
    }

    /** 页面销毁时调用：只断开界面，同步继续在后台跑。 */
    public void detach() {
        listener = null;
    }

    public boolean start(final Context context, final Mode mode, final Callback callback) {
        if (running) {
            return false;
        }
        if (!ACTIVE.compareAndSet(false, true)) {
            callback.onFinished(false, "正在同步中，请稍候");
            return false;
        }
        listener = callback;
        running = true;
        cancelled = false;
        lastStatus = "";
        lastDetail = "";
        lastPercent = 0;
        finished = false;
        finishedSuccess = false;
        finishedMessage = "";
        current = this;
        new Thread(() -> {
            try {
                sync(context.getApplicationContext(), mode, forward);
            } catch (Exception e) {
                Log.w(TAG, "同步异常", e);
                forward.onFinished(false, "同步失败：" + e.getMessage());
            } finally {
                running = false;
                ACTIVE.set(false);
                if (current == this) {
                    current = null;
                }
            }
        }, "lan-sync").start();
        return true;
    }

    public void cancel() {
        cancelled = true;
        LanDiscovery finder = discovery;
        if (finder != null) {
            finder.cancel();
        }
    }

    private void sync(Context context, Mode mode, Callback callback) {
        Song playing = PlaylistProvider.getCurrent();
        String keepSongId = playing == null ? null : playing.getId();

        JSONObject localIndex = LanLibrary.readIndex(context);
        List<Song> localSongs = LanLibrary.load(context);
        // 同步开始时手表上真正能看到的歌（索引里有、音频文件也在）
        Set<String> visibleIds = new HashSet<>();
        for (Song item : localSongs) {
            if (item.getId() != null) {
                visibleIds.add(item.getId());
            }
        }

        callback.onStatus("正在搜索手机…", "请确认手表和手机连的是同一个 Wi-Fi");
        String token = LanPairing.getToken(context);
        discovery = new LanDiscovery(token);
        LanDevice device = discovery.search(LanConstants.DISCOVERY_TIMEOUT_MS);

        if (cancelled) {
            callback.onFinished(false, "已取消");
            return;
        }
        if (device == null) {
            callback.onFinished(false, "没有找到手机端，请确认同一 Wi-Fi 且手机端已开启互联");
            return;
        }

        LanClient client = new LanClient(device, token);
        callback.onStatus("已连接 " + device.name, device.host + ":" + device.port);

        JSONObject playlist;
        try {
            playlist = client.getJson(LanConstants.PATH_PLAYLIST);
        } catch (Exception e) {
            callback.onFinished(false, "读取歌单失败：" + e.getMessage());
            return;
        }

        JSONArray remote = playlist.optJSONArray("songs");
        if (remote == null) {
            // 连 songs 字段都没有，说明对面不是这套协议，别拿它去动本地曲库
            callback.onFinished(false, "手机端清单格式不对，请更新手机端 App");
            return;
        }
        String revision = playlist.optString("revision", "");
        String playlistId = playlist.optString("playlistId", "");
        String playlistName = playlist.optString("playlistName", "手机歌单");
        // 勾选的歌单分组，原样存进索引，左侧歌单页按它列出各个歌单。
        // 老版手机端清单里没有这个字段，就按「整份清单是一个歌单」补一份，保证下面能先把歌单建出来
        JSONArray playlists = normalizeGroups(playlist.optJSONArray("playlists"),
                playlistId, playlistName, remote);

        // 自动同步：版本一致且本地文件齐全就不折腾了
        if (mode == Mode.AUTO && !revision.isEmpty()
                && revision.equals(localIndex.optString("revision"))
                && localSongs.size() == remote.length()) {
            LanBeacon.markSynced(revision);
            // 也要告诉手机一轮结束了，否则它那边会一直显示「同步中」。
            // 这一轮一首都没传，统计按 0 报，手机那边显示「没有需要传的歌」
            reportFinish(client, remote.length(), 0, 0, 0, false, "");
            callback.onFinished(true, "已是最新");
            return;
        }
        // remote.length()==0 是手机端「一个歌单都没勾」的明确指令，不是异常：
        // 下面照常走（循环一次都不进），收尾时把索引清空、文件全删。

        Map<String, JSONObject> previous = entriesOf(localIndex);
        Set<String> remoteIds = new HashSet<>();

        // 本次清单里的全部曲目 id：先删和收尾清理都按它算
        for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item != null) {
                remoteIds.add(item.optString("id", "idx-" + i));
            }
        }

        // 第一步（先删）：没勾选的歌单、歌单里删掉的歌，音频/歌词/封面现在就清掉。
        // 紧接着重建歌单结构，手表左侧的歌单列表立刻只剩这次勾选的那些。
        // 清单为空（手机端要求清空）时不在这里删，收尾统一处理，避免正在放的歌先把文件抽走。
        int earlyRemoved = 0;
        if (remote.length() > 0) {
            earlyRemoved = LanLibrary.removeMissing(context, remoteIds);
        }

        // 第二步：把歌单结构建出来（新增的歌单出现、未勾选的歌单消失）。
        // 索引里写入手机端这次给的全部歌单（每个歌单的曲目 id 都在，只是文件还没到），
        // 已经在前几次同步里下好的歌原样留着 —— 手表左侧歌单页立刻就能看到这些歌单，
        // 曲目数从 0 开始，后面每下完一首就涨一首。
        if (remote.length() > 0) {
            int seeded = savePartialIndex(context, playlistId, playlistName,
                    new LinkedHashMap<>(), previous, remote, playlists);
            if (seeded > 0) {
                applyPlaylist(context, keepSongId);
            } else {
                // 本次清单里一首可用的歌都还没落盘（首次同步、或者换了音质 id 全变），
                // 这时候只让歌单页刷新出新歌单，别把正在放的播放列表清空
                notifyLibraryChanged(context);
            }
            callback.onStatus("已创建 " + playlists.length() + " 个歌单",
                    (earlyRemoved > 0 ? "已删除 " + earlyRemoved + " 个旧文件 · " : "")
                            + "开始接收 " + remote.length() + " 首");
        }

        final int total = remote.length();
        final SyncShared shared = new SyncShared(context, client, callback, previous, remote,
                playlists, playlistId, playlistName, visibleIds, keepSongId, total);

        // 并发下载：默认 5 路。手机端那边也是 5 路并行准备，两边对上，
        // 每首歌都要经过「手机下载音频 → 手表拉走」两跳，串行跑一首歌要等两跳都完。
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final JSONObject song = item;
            final int position = i;
            tasks.add(() -> {
                syncOne(shared, song, position);
                return null;
            });
        }
        if (!tasks.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(SYNC_PARALLEL, tasks.size()), runnable -> {
                        Thread thread = new Thread(runnable, "lan-sync-worker");
                        thread.setDaemon(true);
                        return thread;
                    });
            try {
                pool.invokeAll(tasks);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
            }
        }

        if (cancelled) {
            // 每首落盘时已经写过索引，这里再兜一次，并把 revision 留空（未同步完，下次必然重跑）
            synchronized (shared.indexLock) {
                savePartialIndex(context, playlistId, playlistName, shared.entries, previous,
                        remote, playlists);
                applyPlaylist(context, keepSongId);
            }
            String message = "已取消，已保存 " + shared.finished.get() + " 首";
            reportFinish(client, total, shared.downloaded.get(), shared.skipped.get(), 0, true, message);
            callback.onFinished(false, message);
            return;
        }

        // 手机端已经不在清单里的歌（取消勾选的歌单、歌单里删掉的歌）连文件一起清掉；
        // 目录里没有索引记录的孤儿文件也一并收拾，避免越积越多
        int removedSongs = 0;
        for (String oldId : previous.keySet()) {
            if (!remoteIds.contains(oldId)) {
                removedSongs++;
            }
        }
        Set<String> keepIds = new HashSet<>(remoteIds);

        // 并发落盘，完成顺序是乱的；索引必须按手机端清单顺序写，否则手表里曲目顺序会乱
        saveIndex(context, playlistId, playlistName, revision,
                entriesInOrder(remote, shared.entries), playlists);
        applyPlaylist(context, keepSongId);
        LanLibrary.prune(context, keepIds);

        // 完整跑完才记版本：中途取消的话下次还要接着传
        LanBeacon.markSynced(revision);

        if (remote.length() == 0) {
            // 手机端没勾任何歌单 = 清空手表。文件、索引、封面都没了，正在放的那首也得停，
            // 否则 MediaPlayer 还握着已删除文件的句柄继续出声
            stopPlayback(context);
            String message = localSongs.isEmpty()
                    ? "手机端未选择歌单，手表已经是空的"
                    : "已清空手表上的 " + localSongs.size() + " 首";
            reportFinish(client, 0, 0, 0, localSongs.size(), false, message);
            callback.onFinished(true, message);
            return;
        }

        List<Song> result = PlaylistProvider.getSongs();
        int downloaded = shared.downloaded.get();
        int skipped = shared.skipped.get();
        String skipText = skipped > 0 ? "，跳过已同步 " + skipped + " 首" : "";
        // 不管哪个分支都要先把统计发给手机：它那边据此结束任务、显示结果
        reportFinish(client, total, downloaded, skipped, removedSongs, false, "");
        if (mode == Mode.AUTO) {
            if (downloaded == 0 && removedSongs == 0) {
                callback.onFinished(true, "已是最新");
            } else if (downloaded == 0) {
                callback.onFinished(true, "已移除 " + removedSongs + " 首");
            } else if (removedSongs == 0) {
                callback.onFinished(true, "已同步 " + downloaded + " 首" + skipText);
            } else {
                callback.onFinished(true, "已同步 " + downloaded + " 首，移除 " + removedSongs + " 首" + skipText);
            }
        } else {
            String detail = "同步完成，手表上共 " + result.size() + " 首" + skipText;
            if (removedSongs > 0) {
                detail = "同步完成，手表上共 " + result.size() + " 首（移除 " + removedSongs + " 首）" + skipText;
            }
            callback.onFinished(true, detail);
        }
    }

    /**
     * 把这一轮同步的统计发给手机端。手机收到后会把界面从「同步中」切成「已完成」，
     * 没开自动同步的话还会自己把服务停掉 —— 用户不用再手动点一次「停止同步」。
     * 老版本手机端没有这个接口，404 会被 LanClient 吞掉。
     */
    private static void reportFinish(LanClient client, int total, int downloaded, int skipped,
                                     int removed, boolean cancelled, String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("total", total);
            payload.put("downloaded", downloaded);
            payload.put("skipped", skipped);
            payload.put("removed", removed);
            payload.put("cancelled", cancelled);
            payload.put("message", message == null ? "" : message);
        } catch (Exception ignored) {
        }
        client.finish(payload);
    }

    /** 并发同步时各线程共享的状态 */
    private static final class SyncShared {
        final Context context;
        final LanClient client;
        final Callback callback;
        final Map<String, JSONObject> previous;
        final JSONArray remote;
        final JSONArray playlists;
        final String playlistId;
        final String playlistName;
        final Set<String> visibleIds;
        final String keepSongId;
        final int total;
        /** 已落盘的歌曲条目，key 是曲目 id */
        final Map<String, JSONObject> entries = new ConcurrentHashMap<>();
        /** 正在下载的那几首各自的百分比，用来算总进度 */
        final Map<String, Integer> innerPercent = new ConcurrentHashMap<>();
        final AtomicInteger finished = new AtomicInteger();
        final AtomicInteger downloaded = new AtomicInteger();
        /** 本次因为「本地已经有完整文件」而没有重新传输的曲目数 */
        final AtomicInteger skipped = new AtomicInteger();
        /** 写索引 + 刷新播放列表必须串行，不然两个线程会各写各的 */
        final Object indexLock = new Object();

        SyncShared(Context context, LanClient client, Callback callback,
                   Map<String, JSONObject> previous, JSONArray remote, JSONArray playlists,
                   String playlistId, String playlistName, Set<String> visibleIds,
                   String keepSongId, int total) {
            this.context = context;
            this.client = client;
            this.callback = callback;
            this.previous = previous;
            this.remote = remote;
            this.playlists = playlists;
            this.playlistId = playlistId;
            this.playlistName = playlistName;
            this.visibleIds = visibleIds;
            this.keepSongId = keepSongId;
            this.total = total;
        }
    }

    /**
     * 重新取一次歌词，取不到就隔一会儿再试。
     *
     * 手机端「歌词改了」是靠清单里的指纹通知过来的，等手表开始同步时手机端可能还在
     * 重新准备这份歌词，这时请求会拿到 503（或者 404）—— 直接放弃的话这一轮就白跑了。
     */
    private String fetchLyricWithRetry(SyncShared s, String id) {
        for (int attempt = 0; attempt < LYRIC_FETCH_RETRY; attempt++) {
            if (cancelled) {
                return null;
            }
            try {
                String text = s.client.getTextOrNull(LanConstants.PATH_LYRIC + LanClient.encode(id));
                if (text != null) {
                    return text;
                }
            } catch (Exception ignored) {
            }
            if (attempt < LYRIC_FETCH_RETRY - 1) {
                try {
                    Thread.sleep(LYRIC_FETCH_RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /** 一首歌：等手机端准备好 → 拉音频 → 补歌词封面 → 落盘入库 */
    private void syncOne(SyncShared s, JSONObject item, int position) {
        if (cancelled) {
            return;
        }
        String id = item.optString("id", "idx-" + position);
        String name = item.optString("name", "未命名");
        String artist = item.optString("artist", "");

        JSONObject audioInfo = item.optJSONObject("audio");
        String ext = audioInfo == null ? "mp3" : audioInfo.optString("ext", "mp3");
        long expectSize = audioInfo == null ? -1L : audioInfo.optLong("size", -1L);

        File audio = LanLibrary.findCachedAudio(s.context, id, ext, expectSize);
        boolean cached = audio != null;

        if (cached) {
            // 这首上次已经传完了：不问手机要 prepare、不传音频，直接记账
            s.skipped.incrementAndGet();
            // 顺带告诉手机「这首不用准备了」，否则它那边的列表会一直停在「待准备」。
            // 旧版手机端没这个接口，404 就算了，不影响同步。
            s.client.skip(id);
        } else {
            audio = LanLibrary.audioFile(s.context, id, ext);
            if (!waitUntilReady(s.client, id)) {
                s.callback.onStatus("《" + name + "》跳过", "手机端准备超时或失败");
                return;
            }
            try {
                s.client.download(LanConstants.PATH_AUDIO + LanClient.encode(id), audio,
                        new LanClient.DownloadListener() {
                            @Override
                            public void onProgress(long done, long totalBytes) {
                                int inner = totalBytes > 0 ? (int) (done * 100 / totalBytes) : 0;
                                s.innerPercent.put(id, inner);
                                publishProgress(s);
                            }

                            @Override
                            public boolean isCancelled() {
                                return cancelled;
                            }
                        });
                s.downloaded.incrementAndGet();
            } catch (Exception e) {
                s.innerPercent.remove(id);
                s.callback.onStatus("《" + name + "》接收失败", e.getMessage());
                return;
            }
        }
        s.innerPercent.remove(id);

        JSONObject old = s.previous.get(id);
        String lyric = old == null ? null : old.optString("lyric", null);
        // 手机端调过这首歌的歌词（手动匹配 / 时间轴偏移）时会带一个版本指纹；
        // 和本地索引记的对不上就重拉一份。没调过（指纹为空）或者老版本手机端不给这个字段，
        // 两边都是空串，比较相等，沿用本地缓存、不重复传输。
        String lyricRevision = item.optString("lyricRevision", "");
        String oldLyricRevision = old == null ? "" : old.optString("lyricRevision", "");
        boolean lyricStale = !lyricRevision.equals(oldLyricRevision);
        boolean lyricRefreshed = false;
        if (lyric == null || lyricStale) {
            // 手机端有这首歌的调整版本（带指纹），或者本地这份已经过期：
            // 都可能撞上「手机端还在重新准备」，取不到就隔一会儿再试几次。
            // 手机端还没准备好时会回 503，不是 404，所以这里按「抛异常也重试」处理。
            boolean retryable = lyricStale || !lyricRevision.isEmpty();
            String fetched = null;
            if (retryable) {
                fetched = fetchLyricWithRetry(s, id);
            } else {
                try {
                    fetched = s.client.getTextOrNull(LanConstants.PATH_LYRIC + LanClient.encode(id));
                } catch (Exception ignored) {
                }
            }
            // 全都失败就保留旧歌词，并且不写新指纹（下一轮同步会再试一次）
            if (fetched != null) {
                lyric = fetched;
                lyricRefreshed = true;
            }
        }

        String coverName = old == null ? null : old.optString("cover", null);
        File cover = LanLibrary.coverFile(s.context, id);
        if (coverName == null || !cover.exists()) {
            try {
                s.client.download(LanConstants.PATH_COVER + LanClient.encode(id), cover, null);
                coverName = cover.getName();
            } catch (Exception ignored) {
                coverName = null;
            }
        }

        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("name", name);
            entry.put("artist", artist);
            entry.put("audio", audio.getName());
            entry.put("duration", item.optLong("duration", 0L));
            if (coverName != null) {
                entry.put("cover", coverName);
            }
            if (lyric != null) {
                entry.put("lyric", lyric);
            }
            // 只有歌词确实是这一版指纹对应的内容时才记下来，否则下一轮才会重试；
            // 记错了会让「手机端改了歌词但手表没拿到」永远卡住
            if (!lyricRevision.isEmpty() && (lyricRefreshed || !lyricStale)) {
                entry.put("lyricRevision", lyricRevision);
            }
        } catch (Exception ignored) {
        }
        s.entries.put(id, entry);
        int finished = s.finished.incrementAndGet();

        // 新歌（或之前文件缺失看不到的）落盘后立刻入库，手表马上能播；
        // 旧索引里已有、文件还在的歌本来就在歌单里，不用重复刷
        if (!s.visibleIds.contains(id)) {
            synchronized (s.indexLock) {
                savePartialIndex(s.context, s.playlistId, s.playlistName, s.entries, s.previous,
                        s.remote, s.playlists);
                applyPlaylist(s.context, s.keepSongId);
            }
        }

        publishProgress(s);
        s.callback.onStatus((cached ? "已同步过《" : "已完成《") + name + "》", finished + "/" + s.total);

        Song song = LanLibrary.toSong(s.context, entry);
        if (song != null) {
            s.callback.onSongReady(song, finished, s.total);
        }
    }

    /** 总进度 = 已完成的歌 + 正在下载那几首的百分比，再除以总曲目数 */
    private void publishProgress(SyncShared s) {
        if (s.total <= 0) {
            return;
        }
        double done = s.finished.get();
        for (Integer value : s.innerPercent.values()) {
            done += value / 100.0;
        }
        int percent = (int) Math.max(0, Math.min(100, done * 100.0 / s.total));
        s.callback.onProgress(percent);
    }

    /** 按手机端清单顺序整理落盘结果，保证手表里的曲目顺序跟歌单一致 */
    private Map<String, JSONObject> entriesInOrder(JSONArray remote, Map<String, JSONObject> entries) {
        Map<String, JSONObject> ordered = new LinkedHashMap<>();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "idx-" + i);
            JSONObject entry = entries.get(id);
            if (entry != null) {
                ordered.put(id, entry);
            }
        }
        return ordered;
    }

    /**
     * 手机端清单里的歌单分组兜底。
     * <p>
     * 新版手机端会给 `playlists`（每个歌单的 id / 名称 / 曲目 id 列表）；老版本没有这个字段，
     * 就按「整份清单是一个歌单」补一份 —— 这样「同步开始先建歌单」在任何版本上都能成立。
     * 清单本身是空的（手机端要求清空手表）时返回空数组，别凭空造一个歌单出来。
     */
    private JSONArray normalizeGroups(JSONArray playlists, String playlistId,
                                      String playlistName, JSONArray remote) {
        if (remote.length() == 0) {
            return new JSONArray();
        }
        if (playlists != null && playlists.length() > 0) {
            return playlists;
        }
        JSONArray groups = new JSONArray();
        JSONArray ids = new JSONArray();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item != null) {
                ids.put(item.optString("id", "idx-" + i));
            }
        }
        JSONObject group = new JSONObject();
        try {
            group.put("id", playlistId == null || playlistId.isEmpty() ? "lan-all" : playlistId);
            group.put("name", playlistName == null || playlistName.isEmpty() ? "手机歌单" : playlistName);
            group.put("songIds", ids);
            groups.put(group);
        } catch (Exception ignored) {
        }
        return groups;
    }

    private void saveIndex(Context context, String playlistId, String playlistName,
                           String revision, Map<String, JSONObject> entries,
                           JSONArray playlists) {
        JSONArray array = new JSONArray();
        for (JSONObject entry : entries.values()) {
            array.put(entry);
        }
        JSONObject index = new JSONObject();
        try {
            index.put("playlistId", playlistId);
            index.put("playlistName", playlistName);
            index.put("revision", revision);
            index.put("syncedAt", System.currentTimeMillis());
            index.put("songs", array);
            if (playlists != null) {
                index.put("playlists", playlists);
            }
        } catch (Exception ignored) {
        }
        LanLibrary.saveIndex(context, index);
    }

    /**
     * 同步途中的中间索引：按手机清单顺序，把「本次已经落盘的」和「旧索引里还没轮到的」拼起来。
     * <p>
     * 这样边同步边能看到歌，又不会因为索引里只有前几首就把还没同步到的旧歌从列表里抹掉。
     * revision 故意留空——空版本号表示这份索引进度不完整，下次同步一定重跑（不会被 AUTO 误判成「已是最新」）。
     *
     * @return 写进索引、且音频文件确实还在的歌数
     */
    private int savePartialIndex(Context context, String playlistId, String playlistName,
                                 Map<String, JSONObject> done, Map<String, JSONObject> previous,
                                 JSONArray remote, JSONArray playlists) {
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "idx-" + i);
            JSONObject entry = done.get(id);
            if (entry == null) {
                entry = previous.get(id);
            }
            if (entry != null) {
                merged.put(id, entry);
            }
        }
        saveIndex(context, playlistId, playlistName, "", merged, playlists);
        return merged.size();
    }

    /** 只通知歌单页刷新，不动播放列表。 */
    private void notifyLibraryChanged(Context context) {
        LocalBroadcastManager.getInstance(context)
                .sendBroadcast(new Intent(PlayerActions.ACTION_LIBRARY_CHANGED));
    }

    /** 把曲库读回播放列表，并通知各页面刷新。 */
    private void applyPlaylist(Context context, String keepSongId) {
        // 边同步边刷新时，同步开始时记下的那首可能已经被换过，优先跟着「此刻正在播的」走
        Song playing = PlaylistProvider.getCurrent();
        String keep = playing != null ? playing.getId() : keepSongId;
        List<Song> songs = LanLibrary.load(context);
        PlaylistProvider.replaceSongs(songs, keep);
        LocalBroadcastManager.getInstance(context)
                .sendBroadcast(new Intent(PlayerActions.ACTION_PLAYLIST_CHANGED));
    }

    /**
     * 让播放器服务把当前播放任务丢掉（手机端清空曲库时调用）。
     * 服务已经在跑，这里只是补一条指令；后台被系统限制时忽略即可，
     * 界面那边收到 {@link PlayerActions#ACTION_PLAYLIST_CHANGED} 也会自己清。
     */
    private void stopPlayback(Context context) {
        try {
            context.startService(new Intent(context, MusicPlayerService.class)
                    .putExtra(MusicPlayerService.EXTRA_CLEAR_PLAYLIST, true));
        } catch (Exception e) {
            Log.w(TAG, "停止播放失败: " + e.getMessage());
        }
    }

    private Map<String, JSONObject> entriesOf(JSONObject index) {
        Map<String, JSONObject> map = new HashMap<>();
        JSONArray array = index.optJSONArray("songs");
        if (array == null) {
            return map;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry != null) {
                map.put(entry.optString("id"), entry);
            }
        }
        return map;
    }

    /**
     * 轮询手机端准备状态，ready 返回 true。
     */
    private boolean waitUntilReady(LanClient client, String id) {
        long deadline = System.currentTimeMillis() + LanConstants.PREPARE_TIMEOUT_MS;
        while (!cancelled && System.currentTimeMillis() < deadline) {
            try {
                String state = client.prepare(id);
                if ("ready".equals(state)) {
                    return true;
                }
                if ("failed".equals(state)) {
                    return false;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }
}
