package cn.wearbbs.music.application;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

import cn.wearbbs.music.lan.LanAutoSync;
import cn.wearbbs.music.lan.LanBeacon;
import cn.wearbbs.music.lan.LanLibrary;
import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlaylistProvider;

/**
 * @author JackuXL
 */
public class MainApplication extends Application {

    @SuppressLint("StaticFieldLeak")
    private static Context context;

    /**
     * 播放器服务切歌时（含播完自动连播）靠这条广播把「当前是第几首」写回播放列表，
     * 同时落下「上次播到哪」。挂在 Application 上，界面没打开时也不会丢。
     */
    private final BroadcastReceiver playbackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PlayerActions.ACTION_SONG_CHANGED.equals(intent.getAction())) {
                return;
            }
            PlaylistProvider.syncCurrentIndex(
                    intent.getIntExtra(PlayerActions.EXTRA_INDEX, PlaylistProvider.getCurrentIndex()),
                    intent.getStringExtra(PlayerActions.EXTRA_SONG_ID));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();

        // 先读 assets 里的兜底歌单，再用局域网同步下来的本地曲库覆盖
        PlaylistProvider.loadFromAssets(this);
        List<Song> local = LanLibrary.load(this);
        if (!local.isEmpty()) {
            PlaylistProvider.setSongs(local);
        }
        // 上次退出时播到哪首，启动就停在哪首（等用户按播放）
        PlaylistProvider.restoreLastPlayed();

        LocalBroadcastManager.getInstance(this).registerReceiver(playbackReceiver,
                new IntentFilter(PlayerActions.ACTION_SONG_CHANGED));

        // 连上手机（同一 Wi-Fi）后自动对齐手机端选中的歌单
        LanAutoSync.init(this);
        // 常驻监听手机广播：复用最近一次广播，手机端换了歌单会立刻触发同步
        LanBeacon.start(this);
    }

    public static Context getContext() {
        return context;
    }
}
