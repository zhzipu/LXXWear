package cn.wearbbs.music.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import cn.wearbbs.music.R;
import cn.wearbbs.music.player.PlaybackItem;
import cn.wearbbs.music.player.PlayOrder;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.ui.MainActivity;

/**
 * 播放器服务：MediaPlayer + 系统媒体会话（MediaSession）。
 * <p>
 * 注册媒体会话是为了把手表上的系统播放控件接上——表盘/下拉控制中心里的
 * 播放、暂停、上一首、下一首都通过会话回调进来，通知栏也用 MediaStyle 显示同一份信息。
 * 播放列表通过 startService 的 extras（items / index）传入，控制通过 Binder 完成。
 */
public class MusicPlayerService extends Service {

    private static final String TAG = "MusicPlayerService";

    private static final String CHANNEL_ID = "lxwear_playback";
    private static final int NOTIFICATION_ID = 1001;
    /** 通知大图标最大边长：手表屏幕小，封面不需要原图。 */
    private static final int COVER_MAX_PX = 320;

    // 播放顺序：四种之间由界面轮换（见 player.PlayOrder），服务只负责按它决定下一首是谁。
    /** 列表顺序：按列表往下放，最后一首放完就停。 */
    public static final int ORDER_SEQUENCE = 0;
    /** 单曲循环。 */
    public static final int ORDER_REPEAT_ONE = 1;
    /** 列表循环：放到最后一首回到第一首。 */
    public static final int ORDER_REPEAT_ALL = 2;
    /** 列表随机：下一首随机挑，不重复当前这首。 */
    public static final int ORDER_SHUFFLE = 3;

    /** startService 时带上它（值为 true）：停掉播放并丢掉地址表，手机端清空曲库时用。 */
    public static final String EXTRA_CLEAR_PLAYLIST = "clearPlaylist";
    /** {@link PlaybackItem} 数组。 */
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_AUTO_PLAY = "autoPlay";

    /** 通知里三个按钮的动作，由 onStartCommand 接住。 */
    public static final String ACTION_TOGGLE = "cn.wearbbs.music.service.action.TOGGLE";
    public static final String ACTION_PREVIOUS = "cn.wearbbs.music.service.action.PREVIOUS";
    public static final String ACTION_NEXT = "cn.wearbbs.music.service.action.NEXT";
    public static final String ACTION_STOP = "cn.wearbbs.music.service.action.STOP";

    /** 系统媒体按键/通知按钮需要的全部能力。 */
    private static final long SESSION_ACTIONS = PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_SEEK_TO
            | PlaybackStateCompat.ACTION_STOP;

    private final MyBinder binder = new MyBinder();

    private MediaPlayer player;
    private MediaSessionCompat session;
    private NotificationManager notificationManager;

    private PlaybackItem[] items = new PlaybackItem[0];
    private int index;
    private boolean prepared;
    /** 正在 prepareAsync，界面据此显示「缓冲中」而不是一直转圈。 */
    private boolean preparing;
    private int order = ORDER_SEQUENCE;
    /** 随机模式用，固定种子没必要，每次进程一份就行。 */
    private final java.util.Random random = new java.util.Random();
    /**
     * 随机模式的待播队列：整张表洗好后一个个取，取完才算一轮结束。
     * 和手机端一致——随机不是「每次抽一首」，而是一轮之内不重复。
     */
    private final java.util.ArrayDeque<Integer> shuffleQueue = new java.util.ArrayDeque<>();
    /** 随机模式下拍过的曲目（最近的在前），「上一首」沿它回退。 */
    private final java.util.ArrayDeque<Integer> shuffleHistory = new java.util.ArrayDeque<>();
    /** 洗牌时表有多长；表被换掉（同步补歌、清空）时用来判断要不要重洗。 */
    private int shuffleSize = -1;
    /** 是否已经进过前台（决定是 startForeground 还是 notify 更新通知）。 */
    private boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        // 服务每次重建都以本地存的模式为准，别悄悄退回顺序播放
        order = PlayOrder.get();
        player = new MediaPlayer();
        player.setOnPreparedListener(mp -> {
            prepared = true;
            preparing = false;
            startPlayback();
            updateSession();
            updateNotification();
        });
        player.setOnCompletionListener(mp -> onTrackFinished());
        player.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            preparing = false;
            Toast.makeText(getApplicationContext(), R.string.play_failed, Toast.LENGTH_SHORT).show();
            updateSession();
            updateNotification();
            return true;
        });
        createSession();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE.equals(action)) {
                toggle();
                return START_STICKY;
            }
            if (ACTION_PREVIOUS.equals(action)) {
                binder.previous();
                return START_STICKY;
            }
            if (ACTION_NEXT.equals(action)) {
                binder.next();
                return START_STICKY;
            }
            if (ACTION_STOP.equals(action)) {
                // 划掉通知：停声但保留队列，会话还在，系统控件里还能接着播
                pausePlayback();
                return START_STICKY;
            }
            if (intent.getBooleanExtra(EXTRA_CLEAR_PLAYLIST, false)) {
                // 手机端把歌全删了：立刻停声，地址表也丢掉
                clearPlaylistInternal();
                return START_STICKY;
            }
            PlaybackItem[] playlist = readItems(intent);
            if (playlist != null && playlist.length > 0) {
                items = playlist;
                int startIndex = intent.getIntExtra(EXTRA_INDEX, 0);
                index = clamp(startIndex);
                if (intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)) {
                    prepare(index);
                } else {
                    // 播放列表刚被同步替换：先只记住位置，等用户按播放再准备，避免后台自己响起来
                    updateSession();
                }
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (player != null) {
            player.reset();
            player.release();
            player = null;
        }
        stopForegroundCompat();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 数据

    private PlaybackItem[] readItems(Intent intent) {
        try {
            java.io.Serializable extra = intent.getSerializableExtra(EXTRA_ITEMS);
            if (extra instanceof PlaybackItem[]) {
                return (PlaybackItem[]) extra;
            }
        } catch (Exception e) {
            Log.w(TAG, "读取播放列表失败: " + e.getMessage());
        }
        return null;
    }

    private PlaybackItem current() {
        return items.length == 0 ? null : items[clamp(index)];
    }

    /**
     * 丢掉当前播放任务：停声、清地址表、状态归零。
     * 手机端把曲库清空（一个歌单都没勾）时调用，避免继续播放已被删除的文件。
     */
    private void clearPlaylistInternal() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
        }
        items = new PlaybackItem[0];
        index = 0;
        shuffleQueue.clear();
        shuffleHistory.clear();
        shuffleSize = -1;
        prepared = false;
        preparing = false;
        updateSession();
        stopForegroundCompat();
    }

    // ------------------------------------------------------------ 播放控制

    private void prepare(int newIndex) {
        prepare(newIndex, true);
    }

    /**
     * @param remember 换歌时要不要记进随机模式的「上一首」历史。
     *                 回退到自己时传 false，否则「上一首」会在两首之间来回打转。
     */
    private void prepare(int newIndex, boolean remember) {
        if (player == null || items.length == 0) {
            preparing = false;
            return;
        }
        int oldIndex = index;
        index = clamp(newIndex);
        if (currentOrder() == ORDER_SHUFFLE) {
            // 手动点到的这首从待播队列里摘掉，免得这一轮里又随机到它
            shuffleQueue.remove(Integer.valueOf(index));
            if (remember && oldIndex != index) {
                shuffleHistory.push(oldIndex);
                if (shuffleHistory.size() > 50) {
                    shuffleHistory.removeLast();
                }
            }
        }
        prepared = false;
        preparing = true;
        player.reset();
        try {
            player.setDataSource(items[index].url);
            player.prepareAsync();
        } catch (Exception e) {
            preparing = false;
            Toast.makeText(getApplicationContext(), R.string.play_unavailable, Toast.LENGTH_SHORT).show();
        }
        onSongChanged();
    }

    private int clamp(int value) {
        if (items.length == 0) {
            return 0;
        }
        return ((value % items.length) + items.length) % items.length;
    }

    private void startPlayback() {
        if (player != null && prepared && !player.isPlaying()) {
            player.start();
            updateSession();
            updateNotification();
        }
    }

    private void pausePlayback() {
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            updateSession();
            updateNotification();
        }
    }

    private void seekTo(int position) {
        if (player != null && prepared) {
            player.seekTo(position);
            updateSession();
        }
    }

    private boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    /**
     * 当前播放顺序：以本地设置（{@link PlayOrder}）为准，而不是只看服务里的缓存。
     * <p>
     * 服务被杀掉重建、binder 还没把模式下发过来时，这里也不会退回顺序播放。
     */
    private int currentOrder() {
        order = PlayOrder.get();
        return order;
    }

    /**
     * 重洗一轮：把除 {@code exclude} 之外的曲目打乱排队。
     * 手机端的做法是「把已播列表排掉再随机」，效果等价。
     */
    private void reshuffle(int exclude) {
        shuffleQueue.clear();
        java.util.ArrayList<Integer> pool = new java.util.ArrayList<>(items.length);
        for (int i = 0; i < items.length; i++) {
            if (i != exclude) {
                pool.add(i);
            }
        }
        java.util.Collections.shuffle(pool, random);
        shuffleQueue.addAll(pool);
        shuffleSize = items.length;
    }

    /** 随机模式取下一首：队列空了（一轮播完）就按当前这首重新洗牌。 */
    private int nextShuffleIndex() {
        if (shuffleSize != items.length) {
            // 表被换过（手机端同步补歌/清空），旧队列里的索引已经不成立了
            reshuffle(index);
        }
        if (shuffleQueue.isEmpty()) {
            reshuffle(index);
        }
        Integer next = shuffleQueue.pollFirst();
        return next == null ? index : next;
    }

    /** 播完一首之后按当前顺序决定接下来放什么。 */
    private void onTrackFinished() {
        int mode = currentOrder();
        if (mode == ORDER_REPEAT_ONE) {
            seekTo(0);
            startPlayback();
            return;
        }
        if (mode == ORDER_SEQUENCE && index >= items.length - 1) {
            // 列表顺序放到最后一首：停在这儿，用户再按播放就重放这首
            prepared = false;
            preparing = false;
            updateSession();
            updateNotification();
            return;
        }
        // 自动连播：索引变了要通知界面，否则界面还停在上一首
        prepare(nextIndex());
    }

    /** 下一首的索引：随机模式从洗好的队列里取，其余按顺序（越界由 clamp 绕回去）。 */
    private int nextIndex() {
        if (currentOrder() == ORDER_SHUFFLE && items.length > 1) {
            return nextShuffleIndex();
        }
        return index + 1;
    }

    /** 通知上的播放/暂停、系统的播放/暂停都走这里。 */
    private void toggle() {
        if (prepared) {
            if (isPlaying()) {
                pausePlayback();
            } else {
                startPlayback();
            }
        } else if (items.length > 0) {
            // 还没准备过（比如刚打开应用）：先准备当前这首，准备完自动开始
            prepare(index);
        }
    }

    /**
     * 切歌后统一收口：更新系统媒体会话与通知栏，并广播出去。
     * <p>
     * 界面靠这条广播知道「播完了自动连播」「系统控件切了歌」，不会出现声音走了、
     * 封面歌名还停在上一首；Application 里的接收者还会顺手把当前这首歌记到本地，
     * 下次启动从这首开始。
     */
    private void onSongChanged() {
        PlaybackItem item = current();
        LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent(PlayerActions.ACTION_SONG_CHANGED)
                        .putExtra(PlayerActions.EXTRA_INDEX, index)
                        .putExtra(PlayerActions.EXTRA_SONG_ID, item == null ? null : item.id));
        updateSession();
        updateNotification();
    }

    // ------------------------------------------------------------ 媒体会话

    private void createSession() {
        session = new MediaSessionCompat(this, "LXXWear");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (prepared) {
                    startPlayback();
                } else if (items.length > 0) {
                    prepare(index);
                }
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                binder.next();
            }

            @Override
            public void onSkipToPrevious() {
                binder.previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onStop() {
                pausePlayback();
            }
        });
        session.setSessionActivity(contentIntent());
        // 有表了才把会话挂到系统上：空会话会让表盘上多出一个点不动的媒体入口
        updateSession();
    }

    private void updateSession() {
        if (session == null) {
            return;
        }
        if (items.length == 0) {
            if (session.isActive()) {
                session.setActive(false);
            }
        } else if (!session.isActive()) {
            session.setActive(true);
        }
        PlaybackItem item = current();
        String title = item == null || TextUtils.isEmpty(item.name)
                ? getString(R.string.msg_noMusic) : item.name;
        String artist = item == null || TextUtils.isEmpty(item.artist)
                ? getString(R.string.unknown) : item.artist;

        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
        if (player != null && prepared) {
            metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.getDuration());
        }
        Bitmap cover = loadCover(item);
        if (cover != null) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        }
        session.setMetadata(metadata.build());

        int state;
        if (isPlaying()) {
            state = PlaybackStateCompat.STATE_PLAYING;
        } else if (preparing) {
            state = PlaybackStateCompat.STATE_BUFFERING;
        } else if (prepared) {
            state = PlaybackStateCompat.STATE_PAUSED;
        } else {
            state = PlaybackStateCompat.STATE_STOPPED;
        }
        // speed 传 1 时系统自己按时间外推进度，不需要我们每秒回来改一次
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(SESSION_ACTIONS)
                .setState(state, Math.max(0, getCurrentPosition()), isPlaying() ? 1f : 0f)
                .build());
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        nm().createNotificationChannel(channel);
    }

    private NotificationManager nm() {
        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        return notificationManager;
    }

    /** 只有真的开始播/缓冲时才进前台并挂通知，单纯打开应用不会凭空多出一条通知。 */
    private void updateNotification() {
        // 已经在通知栏上的话，播完停下也要把按钮刷成「播放」，所以只拦「还没进过前台」的情况
        if (session == null || (!foreground && !prepared && !preparing)) {
            return;
        }
        try {
            Notification notification = buildNotification();
            if (!foreground) {
                startForeground(NOTIFICATION_ID, notification);
                foreground = true;
            } else {
                nm().notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // Android 12 起后台启动前台服务会被拒，这里退化成普通通知，不影响放歌
            Log.w(TAG, "显示播放通知失败: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        PlaybackItem item = current();
        String title = item == null || TextUtils.isEmpty(item.name)
                ? getString(R.string.msg_noMusic) : item.name;
        String artist = item == null || TextUtils.isEmpty(item.artist)
                ? getString(R.string.unknown) : item.artist;
        boolean playing = isPlaying();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentIntent())
                .setDeleteIntent(serviceIntent(ACTION_STOP))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous,
                        getString(R.string.previous), serviceIntent(ACTION_PREVIOUS))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        getString(playing ? R.string.pause : R.string.play),
                        serviceIntent(ACTION_TOGGLE))
                .addAction(android.R.drawable.ic_media_next,
                        getString(R.string.next), serviceIntent(ACTION_NEXT));

        Bitmap cover = loadCover(item);
        if (cover != null) {
            builder.setLargeIcon(cover);
        }

        androidx.media.app.NotificationCompat.MediaStyle style =
                new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2);
        builder.setStyle(style);
        return builder.build();
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent, pendingFlags());
    }

    private PendingIntent serviceIntent(String action) {
        Intent intent = new Intent(this, MusicPlayerService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), intent, pendingFlags());
    }

    private int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private void stopForegroundCompat() {
        if (!foreground) {
            return;
        }
        foreground = false;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
    }

    /**
     * 通知/会话的大图。封面是同步下来的本地文件（没有就返回 null，通知只显示文字）。
     */
    private Bitmap loadCover(PlaybackItem item) {
        if (item == null || TextUtils.isEmpty(item.cover)) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(item.cover, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.min(bounds.outWidth, bounds.outHeight) / COVER_MAX_PX);
            return BitmapFactory.decodeFile(item.cover, options);
        } catch (Exception e) {
            return null;
        }
    }

    private int getCurrentPosition() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private int getDuration() {
        try {
            return player != null && prepared ? player.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public class MyBinder extends Binder {

        public void play() {
            if (prepared) {
                startPlayback();
            } else {
                prepare(index);
            }
        }

        public void pause() {
            pausePlayback();
        }

        public boolean isPlaying() {
            return MusicPlayerService.this.isPlaying();
        }

        public boolean isPrepared() {
            return prepared;
        }

        /** 是否正在准备（缓冲）当前这首，界面据此决定要不要显示滚动进度条。 */
        public boolean isPreparing() {
            return preparing;
        }

        /** 下一首（随机模式下就是随机挑一首）。 */
        public void next() {
            prepare(nextIndex());
        }

        /** 上一首：随机模式下沿播放历史回退，其它模式按列表往前一首。 */
        public void previous() {
            if (currentOrder() == ORDER_SHUFFLE) {
                Integer prev = shuffleHistory.pollFirst();
                if (prev != null && prev != index && prev >= 0 && prev < items.length) {
                    prepare(prev, false);
                    return;
                }
            }
            prepare(index - 1);
        }

        /** 直接切到指定索引。 */
        public void setIndex(int newIndex) {
            prepare(newIndex);
        }

        /**
         * 替换播放列表但不主动播放，等用户按播放（{@link #prepareCurrent()}）或切歌再生效。
         */
        public void setPlaylist(PlaybackItem[] data, int newIndex) {
            if (data == null || data.length == 0) {
                return;
            }
            items = data;
            index = clamp(newIndex);
            // 表可能整张换了：随机队列要重洗，旧索引不作数
            shuffleSize = -1;
            shuffleQueue.clear();
            // 表可能整张换了：封面歌名跟着换，但不动正在播的音频
            updateSession();
            updateNotification();
        }

        /** 用户按播放时调用：把当前索引准备好（准备完成后自动开始播放）。 */
        public void prepareCurrent() {
            prepare(index);
        }

        /** 播放列表被清空了：停声并丢掉地址表。 */
        public void clearPlaylist() {
            clearPlaylistInternal();
        }

        public int getIndex() {
            return index;
        }

        /** 服务里有没有一份可播的表（刚起来、还没被塞表时为 false）。 */
        public boolean hasPlaylist() {
            return items.length > 0;
        }

        /** 当前这首的 id，界面靠它把索引对回 {@code PlaylistProvider} 里正确的歌。 */
        public String getCurrentSongId() {
            PlaybackItem item = current();
            return item == null ? null : item.id;
        }

        public int getCurrentPosition() {
            return MusicPlayerService.this.getCurrentPosition();
        }

        public int getDuration() {
            return MusicPlayerService.this.getDuration();
        }

        public void seekTo(int position) {
            MusicPlayerService.this.seekTo(position);
        }

        public void setOrder(int newOrder) {
            if (order != newOrder) {
                // 换模式等于重新开始一轮：随机队列和历史都不再适用
                shuffleQueue.clear();
                shuffleHistory.clear();
                shuffleSize = -1;
            }
            order = newOrder;
        }

        public int getOrder() {
            return order;
        }
    }
}
