package cn.wearbbs.music.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager2.widget.ViewPager2;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import cn.wearbbs.music.R;
import cn.wearbbs.music.adapter.ViewPagerAdapter;
import cn.wearbbs.music.fragment.PlayerPagerFragment;
import cn.wearbbs.music.fragment.SongListFragment;
import cn.wearbbs.music.lan.LanLibrary;
import cn.wearbbs.music.lan.LanPairing;
import cn.wearbbs.music.player.PlayerActions;

/**
 * 播放器（主界面）：顶部时间 + 横向四列「歌单 / 播放器 / 播放列表 / 控制台」。
 * <p>
 * 打开就停在播放器列：往上滑是它下面的歌词页，往左滑是右边的播放列表（当前队列），
 * 往右滑是左边的歌单。
 */
public class MainActivity extends AppCompatActivity {

    /** 时间栏上左右滑动多少 dp 算一格音量。 */
    private static final float VOLUME_STEP_DP = 12f;
    /** 调完音量后，音量条继续停留的时间。 */
    private static final long VOLUME_BAR_LINGER_MS = 1200;
    /** 换了播放顺序后，模式名在时间栏上停留的时间。 */
    private static final long ORDER_TIP_MS = 1000;

    /** 本次进程是否已经自动弹过配对页，避免返回后再被弹一次。 */
    private static boolean pairPromptShown;
    /** 顶部进度条当前是否在显示播放进度（控制台页拿它当音量条，两边不能互相覆盖）。 */
    private static boolean playbackPage = true;

    private ViewPager2 vpMain;
    private TextView tvTitle;

    private AudioManager audioManager;
    private int maxVolume;
    private float volumeStepPx;
    private float volumeTouchSlop;
    /** 时间栏滑动调音量用的临时坐标与状态。 */
    private float touchDownX;
    private float volumeAnchorX;
    private boolean volumeSwipeMoved;

    /** 顶部进度条是不是应该由播放器页来控制。 */
    public static boolean isPlaybackPage() {
        return playbackPage;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (tvTitle != null) {
                tvTitle.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** 提示时间到，时钟重新接管时间栏（顺便立刻刷新一次，不留空档）。 */
    private final Runnable restoreClock = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(clock);
            handler.post(clock);
        }
    };

    /**
     * 时间栏临时显示一句提示（比如「单曲循环」），1 秒后自动交回时钟。
     * 期间把时钟的刷新摘掉，否则下一秒就被时间盖掉了。
     */
    private void showTitleTip(String text) {
        if (tvTitle == null || TextUtils.isEmpty(text)) {
            return;
        }
        handler.removeCallbacks(clock);
        handler.removeCallbacks(restoreClock);
        tvTitle.setText(text);
        handler.postDelayed(restoreClock, ORDER_TIP_MS);
    }

    /** 在播放列表里选歌、或者同步完成后回到播放器页。 */
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (vpMain == null) {
                return;
            }
            String action = intent.getAction();
            if (PlayerActions.ACTION_SELECT_INDEX.equals(action)
                    || PlayerActions.ACTION_SHOW_PLAYER.equals(action)) {
                showPlayerColumn();
            } else if (PlayerActions.ACTION_ORDER_CHANGED.equals(action)) {
                // 换了播放顺序：时间栏先把模式名亮 1 秒，再交回时钟
                showTitleTip(intent.getStringExtra(PlayerActions.EXTRA_TEXT));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTitle = findViewById(R.id.tv_title);
        vpMain = findViewById(R.id.vp_main);
        vpMain.setOffscreenPageLimit(3);
        vpMain.setSaveEnabled(false);
        vpMain.setAdapter(new ViewPagerAdapter(this));
        // 打开就停在播放器列（歌词在它下面，播放列表在它右边）
        vpMain.setCurrentItem(ViewPagerAdapter.PAGE_PLAYER, false);

        IntentFilter filter = new IntentFilter(PlayerActions.ACTION_SELECT_INDEX);
        filter.addAction(PlayerActions.ACTION_SHOW_PLAYER);
        filter.addAction(PlayerActions.ACTION_ORDER_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter);
        handler.post(clock);

        // 歌单页进了某个歌单的歌曲列表后，点顶部时间栏就退回歌单列表；
        // 在时间栏上左右滑动则是调音量。
        setupTitleBar();

        vpMain.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // 播放器列（含它下面的歌词页）和播放列表用顶部进度条表示播放进度，控制台页用它表示音量
                playbackPage = position != ViewPagerAdapter.PAGE_CONSOLE;
            }
        });

        maybeShowPairing(savedInstanceState);
    }

    /**
     * 松手后把页面状态交回去：控制台页不算播放页，其它页交回播放器页按真实播放进度刷新。
     */
    private final Runnable restoreProgressOwner = () -> {
        if (vpMain == null) {
            return;
        }
        playbackPage = vpMain.getCurrentItem() != ViewPagerAdapter.PAGE_CONSOLE;
    };

    /**
     * 顶部时间栏：轻点保持原来的「回歌单列表」，左右滑动用来调音量。
     * 滑动时把顶部进度条临时当音量条用，松手后交回播放器页。
     */
    private void setupTitleBar() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        }
        volumeStepPx = Math.max(1f, VOLUME_STEP_DP * getResources().getDisplayMetrics().density);
        volumeTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        View titleBar = findViewById(R.id.main_title);
        titleBar.setOnClickListener(v -> handleTitleClick());
        titleBar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getRawX();
                    volumeAnchorX = touchDownX;
                    volumeSwipeMoved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!volumeSwipeMoved
                            && Math.abs(event.getRawX() - touchDownX) > volumeTouchSlop) {
                        volumeSwipeMoved = true;
                    }
                    if (!volumeSwipeMoved) {
                        return true;
                    }
                    int steps = (int) ((event.getRawX() - volumeAnchorX) / volumeStepPx);
                    if (steps != 0) {
                        // 只推进锚点：滑多远就调几格，不会攒到最后一次跳一大截
                        volumeAnchorX += steps * volumeStepPx;
                        changeVolumeBy(steps);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (volumeSwipeMoved) {
                        handler.postDelayed(restoreProgressOwner, VOLUME_BAR_LINGER_MS);
                    } else {
                        // 没滑动就是一次点击，交回给 OnClickListener
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (volumeSwipeMoved) {
                        handler.postDelayed(restoreProgressOwner, VOLUME_BAR_LINGER_MS);
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /** 右滑加音量、左滑减音量，每滑约 12dp 调一格。 */
    private void changeVolumeBy(int steps) {
        if (audioManager == null || maxVolume <= 0) {
            return;
        }
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = Math.max(0, Math.min(maxVolume, current + steps));
        if (target == current) {
            // 已经到顶/到底：也把音量条亮一下，让用户知道调到头了
            showVolumeBar(current);
            return;
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        showVolumeBar(target);
    }

    /** 时间栏滑动时把音量数字显示 1 秒（顶部那条进度条已经去掉，只留文字提示）。 */
    private void showVolumeBar(int volume) {
        playbackPage = false;
        showTitleTip(getString(R.string.volume_tip_format, volume, maxVolume));
    }

    /**
     * 点顶部时间栏的回退顺序：
     * <ol>
     *   <li>歌单列停在「歌曲列表」层 → 退回「歌单列表」层；</li>
     *   <li>其它列（播放列表、控制台）→ 回播放器列；</li>
     *   <li>播放器列停在歌词页 → 退回播放器页。</li>
     * </ol>
     * <p>
     * 二级页面（播放列表管理、局域网、配对）不在这里处理，由各自的 Activity 做成返回上一页。
     */
    private void handleTitleClick() {
        if (vpMain == null) {
            return;
        }
        int page = vpMain.getCurrentItem();
        if (page == ViewPagerAdapter.PAGE_SONGLIST && backToPlaylistList()) {
            return;
        }
        if (page != ViewPagerAdapter.PAGE_PLAYER) {
            showPlayerColumn();
            return;
        }
        // 已经在播放器列：停在歌词页时退回播放器页（下滑返回的备用入口）
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof PlayerPagerFragment
                    && ((PlayerPagerFragment) fragment).isOnLyricsPage()) {
                ((PlayerPagerFragment) fragment).showPlayerPage();
                return;
            }
        }
    }

    /**
     * 歌单列内部退回歌单列表层。
     *
     * @return 真的退了一层（正停在歌曲列表层）时返回 true；本来就在歌单列表层返回 false。
     */
    private boolean backToPlaylistList() {
        if (vpMain == null || vpMain.getCurrentItem() != ViewPagerAdapter.PAGE_SONGLIST) {
            return false;
        }
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof SongListFragment
                    && ((SongListFragment) fragment).goBackToPlaylists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 回到播放器列，并把上下翻页拨回上面的播放器页（歌词页在下）。
     * 选歌、同步完成、从局域网页返回都走这里。
     */
    private void showPlayerColumn() {
        vpMain.setCurrentItem(ViewPagerAdapter.PAGE_PLAYER, false);
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof PlayerPagerFragment) {
                ((PlayerPagerFragment) fragment).showPlayerPage();
            }
        }
    }

    /**
     * 还没配对、也没同步到任何歌单时（等于什么都播不了），启动直接把配对二维码摆到用户面前。
     * 没连 Wi-Fi 时配对页会提示先连 Wi-Fi，用户可以直接返回。
     */
    private void maybeShowPairing(Bundle savedInstanceState) {
        if (savedInstanceState != null || pairPromptShown) {
            return;
        }
        pairPromptShown = true;
        if (LanPairing.isPaired(this) || !LanLibrary.load(this).isEmpty()) {
            return;
        }
        startActivity(new Intent(this, PairActivity.class));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(clock);
        handler.removeCallbacks(restoreClock);
        handler.removeCallbacks(restoreProgressOwner);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        super.onDestroy();
    }
}
