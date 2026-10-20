package cn.wearbbs.music.lan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import cn.wearbbs.music.model.Song;

/**
 * 自动同步调度：应用启动后、Wi-Fi 状态变化时、以及每 10 分钟检查一次。
 * 只要手机端「当前选中的歌单」版本变了，就自动整单对齐，无需用户操作。
 * 没连 Wi-Fi、找不到手机时静默跳过，不打扰用户。
 */
public final class LanAutoSync {

    private static final String TAG = "LanAutoSync";
    private static final long INTERVAL_MS = 10 * 60 * 1000L;
    private static final long FIRST_DELAY_MS = 2000L;

    private static final Object LOCK = new Object();

    private static Context appContext;
    private static Handler handler;
    private static BroadcastReceiver receiver;
    private static boolean initialized;
    private static volatile boolean syncing;

    private LanAutoSync() {
    }

    /** 在 Application.onCreate 里调用一次。 */
    public static void init(Context context) {
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            initialized = true;
            appContext = context.getApplicationContext();

            HandlerThread thread = new HandlerThread("lan-auto-sync");
            thread.start();
            handler = new Handler(thread.getLooper());

            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    schedule(0L);
                }
            };
            appContext.registerReceiver(receiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
        schedule(FIRST_DELAY_MS);
    }

    /** 外部（比如从同步页回来）可以要求立刻检查一次。 */
    public static void requestNow() {
        schedule(0L);
    }

    public static boolean isSyncing() {
        return syncing;
    }

    private static void schedule(long delayMs) {
        Handler current = handler;
        if (current == null) {
            return;
        }
        current.removeCallbacks(RUNNER);
        current.postDelayed(RUNNER, delayMs);
    }

    private static final Runnable RUNNER = new Runnable() {
        @Override
        public void run() {
            Context context = appContext;
            if (context == null || syncing) {
                schedule(INTERVAL_MS);
                return;
            }
            if (!isOnWifi(context)) {
                schedule(INTERVAL_MS);
                return;
            }
            syncing = true;
            new LanSyncManager().start(context, LanSyncManager.Mode.AUTO,
                    new LanSyncManager.Callback() {
                        @Override
                        public void onStatus(String status, String detail) {
                        }

                        @Override
                        public void onProgress(int percent) {
                        }

                        @Override
                        public void onSongReady(Song song, int finished, int total) {
                        }

                        @Override
                        public void onFinished(boolean success, String message) {
                            syncing = false;
                            Log.i(TAG, "自动同步结束: " + message);
                            schedule(INTERVAL_MS);
                        }
                    });
        }
    };

    @SuppressWarnings("deprecation")
    private static boolean isOnWifi(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        NetworkInfo info = manager.getActiveNetworkInfo();
        return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
    }
}
