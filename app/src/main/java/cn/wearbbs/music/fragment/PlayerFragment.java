package cn.wearbbs.music.fragment;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.Locale;

import cn.wearbbs.music.R;
import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlayOrder;
import cn.wearbbs.music.player.PlaylistProvider;
import cn.wearbbs.music.service.MusicPlayerService;
import cn.wearbbs.music.ui.MainActivity;
import cn.wearbbs.music.util.ToastUtil;
import cn.wearbbs.music.widget.SingleLineLrcView;

/**
 * 播放器页：封面、歌名、歌手（播放中换成单行歌词）、上一首 / 播放暂停 / 下一首。
 */
public class PlayerFragment extends Fragment {

    /** 供歌词页、控制台页复用，服务未连接时为 null。 */
    public static MusicPlayerService.MyBinder binder;

    /** 歌词行刷新间隔：比 500ms 的进度刷新更密，换句的滚动才跟得住拍子。 */
    private static final long LYRIC_TICK_MS = 100;

    private TextView tvName;
    private TextView tvAuthor;
    /** 歌手那一行位置上的单行歌词，只在播放中显示。 */
    private SingleLineLrcView lvInline;
    private ImageView ivCover;
    private ImageView ivStart;
    /** 本曲进度条（顶部那根 3dp 进度条已去掉，进度只看这里）。 */
    private ProgressBar pbSong;
    /** 进度条右端的「当前 / 总时长」。 */
    private TextView tvTime;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean bound;
    /** 服务还没连上时先记下要切换到的索引。 */
    private int pendingIndex = -1;
    /** 服务还没连上时用户就按了播放：连上后自动把当前这首准备起来。 */
    private boolean pendingPlay;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (MusicPlayerService.MyBinder) service;
            binder.setOrder(PlayOrder.get());
            // 播放列表可能刚被同步补过歌，连上后先对齐地址表
            if (!PlaylistProvider.isEmpty()) {
                // 服务里可能已经放到了别的歌（比如熄屏时播完了自动连播），索引以服务为准
                int target = binder.hasPlaylist()
                        ? binder.getIndex() : PlaylistProvider.getCurrentIndex();
                PlaylistProvider.syncCurrentIndex(target, binder.getCurrentSongId());
                binder.setPlaylist(PlaylistProvider.getPlaybackItems(), PlaylistProvider.getCurrentIndex());
                updateSongInfo();
            }
            if (pendingIndex >= 0) {
                // 等待期间曲库可能又被同步补过歌，索引以播放列表里的当前值为准
                binder.setIndex(PlaylistProvider.isEmpty() ? pendingIndex : PlaylistProvider.getCurrentIndex());
                pendingIndex = -1;
            }
            if (pendingPlay) {
                pendingPlay = false;
                binder.prepareCurrent();
            }
            updateProgress();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (PlayerActions.ACTION_SELECT_INDEX.equals(action)) {
                playIndex(intent.getIntExtra(PlayerActions.EXTRA_INDEX, 0));
            } else if (PlayerActions.ACTION_PLAYLIST_CHANGED.equals(action)) {
                onPlaylistChanged();
            } else if (PlayerActions.ACTION_SONG_CHANGED.equals(action)) {
                onSongChanged(intent);
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    /**
     * 歌手/歌词那一行的刷新。
     * <p>
     * 播放中把歌手换成单行歌词，并跟着播放位置换句；没在播（暂停、准备中、歌单空）
     * 就收回歌词、显示歌手。走 100ms 的独立 ticker 是为了换句时机准，别等 500ms 的进度刷新。
     */
    private final Runnable lyricTicker = new Runnable() {
        @Override
        public void run() {
            syncAuthorLine();
            handler.postDelayed(this, LYRIC_TICK_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_player, container, false);

        tvName = view.findViewById(R.id.tv_name);
        tvAuthor = view.findViewById(R.id.tv_author);
        lvInline = view.findViewById(R.id.lv_inline);
        ivCover = view.findViewById(R.id.iv_cover);
        ivStart = view.findViewById(R.id.iv_start);
        pbSong = view.findViewById(R.id.pb_song);
        tvTime = view.findViewById(R.id.tv_time);

        view.findViewById(R.id.iv_start).setOnClickListener(v -> togglePlay());
        // 点封面换播放顺序：单曲循环 → 列表循环 → 列表随机 → 列表顺序
        view.findViewById(R.id.iv_cover).setOnClickListener(v -> cyclePlayOrder(requireContext()));
        view.findViewById(R.id.iv_precious).setOnClickListener(v -> stepSong(-1));
        view.findViewById(R.id.iv_next).setOnClickListener(v -> stepSong(1));

        IntentFilter filter = new IntentFilter();
        filter.addAction(PlayerActions.ACTION_SELECT_INDEX);
        filter.addAction(PlayerActions.ACTION_PLAYLIST_CHANGED);
        filter.addAction(PlayerActions.ACTION_SONG_CHANGED);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver, filter);

        if (PlaylistProvider.isEmpty()) {
            tvName.setText(R.string.msg_noMusic);
            tvAuthor.setText(R.string.unknown);
            ToastUtil.show(requireActivity(), getString(R.string.empty_playlist_tip));
        } else {
            updateSongInfo();
            // 启动不自动播放：只把播放列表交给服务、记住位置，等用户按播放键才出声
            startAndBind(false);
        }

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        handler.post(ticker);
        handler.post(lyricTicker);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(lyricTicker);
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver);
        if (bound) {
            requireContext().unbindService(connection);
            bound = false;
        }
        binder = null;
        lvInline = null;
        super.onDestroyView();
    }

    private void startAndBind(boolean autoPlay) {
        Intent intent = new Intent(requireContext(), MusicPlayerService.class);
        intent.putExtra(MusicPlayerService.EXTRA_ITEMS, PlaylistProvider.getPlaybackItems());
        intent.putExtra(MusicPlayerService.EXTRA_INDEX, PlaylistProvider.getCurrentIndex());
        intent.putExtra(MusicPlayerService.EXTRA_AUTO_PLAY, autoPlay);
        requireContext().startService(intent);
        bound = requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    /**
     * 局域网同步替换了播放列表：刷新界面，并把新的地址表交给播放器（不主动发声）。
     */
    private void onPlaylistChanged() {
        if (PlaylistProvider.isEmpty()) {
            // 手机端把歌单全取消/清空了：停声并回到「无音乐」状态
            if (binder != null) {
                binder.clearPlaylist();
            }
            showEmpty();
            notifySongChanged();
            return;
        }
        updateSongInfo();
        if (binder != null) {
            binder.setPlaylist(PlaylistProvider.getPlaybackItems(), PlaylistProvider.getCurrentIndex());
        } else if (!bound) {
            startAndBind(false);
        }
        notifySongChanged();
    }

    /**
     * 播放器服务换了当前歌（播完自动下一首、系统控件切歌、通知栏按钮）：
     * 这里只负责把界面刷新过来，索引已经由 Application 里的统一接收者写回 {@link PlaylistProvider}。
     */
    private void onSongChanged(Intent intent) {
        if (binder != null && binder.hasPlaylist()
                && binder.getIndex() != PlaylistProvider.getCurrentIndex()) {
            // 广播比界面晚到时兜底：按服务给的 id 对回正确的歌
            String songId = intent.getStringExtra(PlayerActions.EXTRA_SONG_ID);
            PlaylistProvider.syncCurrentIndex(binder.getIndex(),
                    songId != null ? songId : binder.getCurrentSongId());
        }
        if (PlaylistProvider.isEmpty()) {
            showEmpty();
            return;
        }
        updateSongInfo();
        if (ivStart != null) {
            ivStart.setImageResource(binder != null && binder.isPlaying()
                    ? R.drawable.ic_baseline_pause_circle_filled_24
                    : R.drawable.ic_baseline_play_circle_filled_24);
        }
    }

    /** 播放列表为空时的界面状态。 */
    private void showEmpty() {
        tvName.setText(R.string.msg_noMusic);
        tvAuthor.setText(R.string.unknown);
        if (lvInline != null) {
            lvInline.setLrc(null);
            lvInline.setVisibility(View.GONE);
        }
        if (tvAuthor != null) {
            tvAuthor.setVisibility(View.VISIBLE);
        }
        if (ivCover != null) {
            ivCover.setImageResource(R.drawable.ic_baseline_photo_size_select_actual_24);
        }
        if (ivStart != null) {
            ivStart.setImageResource(R.drawable.ic_baseline_play_circle_filled_24);
        }
        resetSongProgress();
    }

    /**
     * 上一首 / 下一首按钮。
     * <p>
     * 交给服务按当前顺序决定（随机模式下就是随机跳一首），不能自己算 index ± 1，
     * 否则随机模式下按「下一首」还是顺序往下走，看着就像随机没生效。
     * 服务还没连上时退回按列表前后走一首。
     */
    private void stepSong(int step) {
        if (PlaylistProvider.isEmpty()) {
            return;
        }
        if (binder != null && binder.hasPlaylist()) {
            if (step > 0) {
                binder.next();
            } else {
                binder.previous();
            }
            return;
        }
        playIndex(PlaylistProvider.getCurrentIndex() + step);
    }

    /** 切到播放列表中的第 index 首。 */
    private void playIndex(int index) {
        if (PlaylistProvider.isEmpty()) {
            return;
        }
        PlaylistProvider.setCurrentIndex(index);
        updateSongInfo();
        if (binder != null) {
            binder.setIndex(PlaylistProvider.getCurrentIndex());
        } else {
            // 服务还没连上（比如刚同步进来第一首就点了）：连上后立刻切到这首
            pendingIndex = PlaylistProvider.getCurrentIndex();
            if (!bound) {
                startAndBind(false);
            }
        }
        notifySongChanged();
    }

    private void togglePlay() {
        if (PlaylistProvider.isEmpty()) {
            // 同步还没传到第一首，或者手机端把歌单清空了
            ToastUtil.show(requireActivity(), getString(R.string.empty_playlist_tip));
            return;
        }
        if (binder == null) {
            // 播放列表在页面建好之后才由同步补进来（服务还没起）：连上后自动开始准备
            pendingPlay = true;
            if (!bound) {
                startAndBind(false);
            }
            ToastUtil.show(requireActivity(), getString(R.string.player_preparing_tip));
            return;
        }
        // 曲库可能刚被同步补过歌：按播放前先把地址表对齐当前播放列表
        binder.setPlaylist(PlaylistProvider.getPlaybackItems(), PlaylistProvider.getCurrentIndex());
        if (!binder.isPrepared()) {
            // 播放列表刚同步进来、还没准备过：开始准备，准备完会自动播放
            binder.prepareCurrent();
            return;
        }
        if (binder.isPlaying()) {
            binder.pause();
            ivStart.setImageResource(R.drawable.ic_baseline_play_circle_filled_24);
        } else {
            binder.play();
            ivStart.setImageResource(R.drawable.ic_baseline_pause_circle_filled_24);
        }
    }

    private void updateSongInfo() {
        Song song = PlaylistProvider.getCurrent();
        if (song == null) {
            return;
        }
        tvName.setText(TextUtils.isEmpty(song.getName()) ? getString(R.string.untitled) : song.getName());
        tvAuthor.setText(TextUtils.isEmpty(song.getArtist()) ? getString(R.string.unknown) : song.getArtist());
        // 换歌了：歌词整段换掉，当前句从头对齐（下一次 tick 会按播放位置摆好）
        if (lvInline != null) {
            lvInline.setLrc(song.getLyric());
        }

        RequestOptions options = RequestOptions.bitmapTransform(new RoundedCorners(15))
                .placeholder(R.drawable.ic_baseline_photo_size_select_actual_24)
                .error(R.drawable.ic_baseline_photo_size_select_actual_24);
        ivCover.setImageResource(R.drawable.ic_baseline_photo_size_select_actual_24);
        if (!TextUtils.isEmpty(song.getCoverUrl())) {
            Glide.with(requireContext()).load(song.getCoverUrl()).apply(options).into(ivCover);
        }
        // 换歌了：封面下那条进度先归零，别让新歌头 0.5 秒顶着上一首的进度
        resetSongProgress();
    }

    /**
     * 歌手那一行：播放中且这首歌有歌词时换成单行歌词，跟着播放位置往上滚；
     * 暂停、准备中、没有歌词、歌单空的时候收回去显示歌手。
     */
    private void syncAuthorLine() {
        if (tvAuthor == null || lvInline == null) {
            return;
        }
        boolean showLyric = binder != null && binder.isPrepared() && binder.isPlaying()
                && lvInline.hasLyric();
        if (!showLyric) {
            if (lvInline.getVisibility() != View.GONE) {
                lvInline.setVisibility(View.GONE);
            }
            if (tvAuthor.getVisibility() != View.VISIBLE) {
                tvAuthor.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (tvAuthor.getVisibility() != View.GONE) {
            tvAuthor.setVisibility(View.GONE);
        }
        if (lvInline.getVisibility() != View.VISIBLE) {
            lvInline.setVisibility(View.VISIBLE);
        }
        lvInline.updateTime(binder.getCurrentPosition());
    }

    /** 封面下那条进度条：还没准备时归零，时长还不知道就显示 --:--。 */
    private void resetSongProgress() {
        if (pbSong != null) {
            pbSong.setMax(100);
            pbSong.setProgress(0);
        }
        updateTimeText(0, 0);
    }

    /** 封面下那条进度条按真实进度走；还没准备好时停在 0。 */
    private void updateSongProgress(boolean ready, int position, int duration) {
        if (!ready) {
            resetSongProgress();
            return;
        }
        if (pbSong != null) {
            pbSong.setMax(duration > 0 ? duration : 1);
            pbSong.setProgress(position);
        }
        updateTimeText(position, duration);
    }

    /**
     * 进度条右端：当前播放位置 / 本曲总时长。
     * 时长还没拿到（准备中、失败）就只在右边显示 --:--。
     */
    private void updateTimeText(int position, int duration) {
        if (tvTime == null) {
            return;
        }
        String current = formatTime(position);
        String total = duration > 0 ? formatTime(duration) : "--:--";
        tvTime.setText(getString(R.string.song_time_format, current, total));
    }

    /** 毫秒转 mm:ss。 */
    private static String formatTime(int ms) {
        int totalSeconds = Math.max(0, ms / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d",
                totalSeconds / 60, totalSeconds % 60);
    }

    private void updateProgress() {
        if (!MainActivity.isPlaybackPage()) {
            return;
        }
        // 服务里已经换了歌（播完自动连播、系统控件切歌）：界面跟着换，别停在上一首
        if (binder != null && binder.hasPlaylist()
                && binder.getIndex() != PlaylistProvider.getCurrentIndex()) {
            PlaylistProvider.syncCurrentIndex(binder.getIndex(), binder.getCurrentSongId());
            updateSongInfo();
            notifySongChanged();
        }
        // 没有播放任务（歌单空、服务没起来）：不显示进度
        if (PlaylistProvider.isEmpty() || binder == null) {
            resetSongProgress();
            return;
        }
        if (!binder.isPrepared()) {
            if (binder.isPreparing()) {
                updateSongProgress(false, 0, 0);
            } else {
                // 歌单有了但还没开始播（比如刚同步完）：也不显示
                resetSongProgress();
            }
            return;
        }
        int duration = binder.getDuration();
        int position = binder.getCurrentPosition();
        updateSongProgress(true, position, duration);
        if (ivStart != null) {
            ivStart.setImageResource(binder.isPlaying()
                    ? R.drawable.ic_baseline_pause_circle_filled_24
                    : R.drawable.ic_baseline_play_circle_filled_24);
        }
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(
                new Intent(PlayerActions.ACTION_POSITION)
                        .putExtra(PlayerActions.EXTRA_POSITION, position));
    }

    private void notifySongChanged() {
        Song current = PlaylistProvider.getCurrent();
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(
                new Intent(PlayerActions.ACTION_SONG_CHANGED)
                        .putExtra(PlayerActions.EXTRA_INDEX, PlaylistProvider.getCurrentIndex())
                        .putExtra(PlayerActions.EXTRA_SONG_ID, current == null ? null : current.getId()));
    }

    public static int getPlayOrder() {
        return PlayOrder.get();
    }

    /** 顺序播放 / 单曲循环 / 列表循环 / 列表随机，切了就落到本地，下次启动沿用。 */
    public static void setPlayOrder(int newOrder) {
        PlayOrder.set(newOrder);
        if (binder != null) {
            binder.setOrder(newOrder);
        }
    }

    /**
     * 换到下一档顺序，并让模式名在时间栏上闪 1 秒。
     * 封面和控制台页的按钮都走这里。
     */
    public static void cyclePlayOrder(Context context) {
        int next = PlayOrder.next(PlayOrder.get());
        setPlayOrder(next);
        if (context == null) {
            return;
        }
        String label = context.getString(PlayOrder.labelRes(next));
        LocalBroadcastManager.getInstance(context).sendBroadcast(
                new Intent(PlayerActions.ACTION_ORDER_CHANGED)
                        .putExtra(PlayerActions.EXTRA_TEXT, label));
    }

    public static void seekTo(int position) {
        if (binder != null) {
            binder.seekTo(position);
        }
    }

    public static boolean isPrepared() {
        return binder != null && binder.isPrepared();
    }
}
