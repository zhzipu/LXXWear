package cn.wearbbs.music.lan;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import cn.wearbbs.music.util.SharedPreferencesUtil;

/**
 * 常驻监听手机端的 UDP 广播（8687）。
 * <p>
 * 以前只在同步时临时开 socket 听 5 秒，导致手机上点了「同步」手表要等下一个轮询周期才知道。
 * 现在进程活着就一直听着：
 * <ul>
 *   <li>同步时直接复用最近收到的广播，不用再等</li>
 *   <li>广播里的 revision 与上次成功同步的不一致，就立刻触发一次自动同步</li>
 * </ul>
 */
public final class LanBeacon {

    private static final String TAG = "LanBeacon";
    /** 上次成功同步完成的歌单版本，手机端换歌单/换音质时 revision 会变 */
    public static final String KEY_LAST_REVISION = "lan_last_success_revision";
    /** 广播超过这个时间没更新就不当作在线 */
    private static final long FRESH_MS = 6000L;
    /** 同一个 revision 最快多久触发一次自动同步，避免同步失败时死循环重试 */
    private static final long TRIGGER_COOLDOWN_MS = 60_000L;

    private static final Object LOCK = new Object();

    private static boolean started;
    private static Context appContext;
    private static DatagramSocket socket;
    private static Thread thread;

    private static String lastHost = "";
    private static int lastPort = LanConstants.DEFAULT_HTTP_PORT;
    private static String lastName = "";
    private static String lastToken = "";
    private static String lastRevision = "";
    private static long lastSeenAt;

    private static String lastTriggeredRevision = "";
    private static long lastTriggerAt;

    private LanBeacon() {
    }

    public interface CancelCheck {
        boolean isCancelled();
    }

    /** 在 Application.onCreate 里调用一次。 */
    public static void start(Context context) {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            started = true;
            appContext = context.getApplicationContext();
        }
        thread = new Thread(LanBeacon::loop, "lan-beacon");
        thread.setDaemon(true);
        thread.start();
    }

    /** 常驻监听是否已经起来了；没起来时调用方要自己开 socket 兜底。 */
    public static boolean isListening() {
        synchronized (LOCK) {
            return socket != null;
        }
    }

    /** 最近一次收到的手机（可能是 null），不阻塞。 */
    public static LanDevice latest(String expectedToken) {
        synchronized (LOCK) {
            if (lastHost.isEmpty()) {
                return null;
            }
            if (System.currentTimeMillis() - lastSeenAt > FRESH_MS) {
                return null;
            }
            if (!expectedToken.isEmpty() && !expectedToken.equals(lastToken)) {
                return null;
            }
            return new LanDevice(lastHost, lastPort, lastName);
        }
    }

    /**
     * 等一台手机出现：先看缓存的广播，没有就边收边等，超时返回 null。
     */
    public static LanDevice waitFor(String expectedToken, long timeoutMs, CancelCheck check) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check != null && check.isCancelled()) {
                return null;
            }
            LanDevice device = latest(expectedToken);
            if (device != null) {
                return device;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    private static void loop() {
        while (true) {
            DatagramSocket current = null;
            try {
                current = new DatagramSocket(null);
                current.setReuseAddress(true);
                current.bind(new InetSocketAddress(LanConstants.DISCOVERY_PORT));
                current.setSoTimeout(1000);
                synchronized (LOCK) {
                    socket = current;
                }
                Log.i(TAG, "开始监听广播 :" + LanConstants.DISCOVERY_PORT);

                byte[] buffer = new byte[2048];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        current.receive(packet);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    handle(packet);
                }
            } catch (Exception e) {
                Log.w(TAG, "广播监听中断: " + e.getMessage());
            } finally {
                if (current != null) {
                    current.close();
                }
                synchronized (LOCK) {
                    socket = null;
                }
            }
            // 出错（比如 Wi-Fi 刚切换）就歇一下重开，别空转
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void handle(DatagramPacket packet) {
        String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        String host = packet.getAddress().getHostAddress();
        try {
            JSONObject object = new JSONObject(json);
            if (!LanConstants.SERVICE_NAME.equals(object.optString("service"))) {
                return;
            }
            String token = object.optString("token", "");
            int port = object.optInt("port", LanConstants.DEFAULT_HTTP_PORT);
            String name = object.optString("name", host);
            String revision = object.optString("revision", "");

            synchronized (LOCK) {
                lastHost = host;
                lastPort = port;
                lastName = name;
                lastToken = token;
                lastRevision = revision;
                lastSeenAt = System.currentTimeMillis();
            }
            Log.i(TAG, "收到广播 " + host + ":" + port + " rev=" + revision
                    + " token=" + shortToken(token));
            maybeTrigger(revision, token, name);
        } catch (Exception e) {
            // 别人的广播，忽略
        }
    }

    /**
     * 手机上换歌单/换音质后 revision 会变，收到新版本就立刻让自动同步跑一次。
     * 不用等 10 分钟的定时轮询，用户点完「同步」手表很快就能开始拉。
     */
    private static void maybeTrigger(String revision, String packetToken, String packetName) {
        if (revision.isEmpty()) {
            return;
        }
        Context context = appContext;
        if (context == null) {
            return;
        }
        // 配对过就只认这台手机，别被同网段别人的广播带节奏
        String paired = LanPairing.getToken(context);
        if (!paired.isEmpty() && !paired.equals(packetToken)) {
            // 手机重装/清数据后自己的 token 会重新生成，旧 token 会让同步永久卡住，
            // 且日志上什么都看不出来。设备名对得上就当作同一台手机换了身份，自动对齐。
            String pairedName = LanPairing.getDeviceName(context);
            if (!packetToken.isEmpty() && !pairedName.isEmpty() && pairedName.equals(packetName)) {
                LanPairing.save(context, packetToken, packetName);
                Log.i(TAG, "手机端 token 已更新，自动对齐");
            } else {
                Log.w(TAG, "广播 token 与配对不一致（本地 " + shortToken(paired)
                        + "，广播 " + shortToken(packetToken) + "），忽略");
                return;
            }
        }
        String synced = SharedPreferencesUtil.getString(KEY_LAST_REVISION, "");
        if (revision.equals(synced)) {
            return;
        }
        if (LanSyncManager.isActive()) {
            Log.i(TAG, "已有同步任务在跑，稍后处理新版本");
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (revision.equals(lastTriggeredRevision) && now - lastTriggerAt < TRIGGER_COOLDOWN_MS) {
                return;
            }
            lastTriggeredRevision = revision;
            lastTriggerAt = now;
        }
        Log.i(TAG, "歌单版本更新，立即同步: " + revision);
        LanAutoSync.requestNow();
    }

    private static String shortToken(String token) {
        if (token == null || token.isEmpty()) {
            return "(空)";
        }
        return token.length() <= 8 ? token : token.substring(0, 8);
    }

    /** 同步成功后记一下版本，避免重复触发。 */
    public static void markSynced(String revision) {
        if (revision == null || revision.isEmpty()) {
            return;
        }
        SharedPreferencesUtil.putString(KEY_LAST_REVISION, revision);
        synchronized (LOCK) {
            if (revision.equals(lastTriggeredRevision)) {
                lastTriggeredRevision = "";
            }
        }
    }
}
