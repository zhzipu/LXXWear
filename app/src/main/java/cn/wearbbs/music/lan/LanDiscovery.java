package cn.wearbbs.music.lan;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * 找到局域网里的 LX 手机端。
 * <p>
 * 进程里常驻着 {@link LanBeacon} 监听广播（8687），同步时优先用它已经收到的包，
 * 手机上刚点完「同步」也能立刻连上；常驻监听没起来时退回自己开 socket 听一会儿。
 * <p>
 * 广播包示例：{"service":"lxnx-lan","version":1,"port":8686,"name":"我的手机","token":"...","revision":"..."}
 */
public class LanDiscovery {

    private static final String TAG = "LanDiscovery";

    private volatile boolean cancelled;
    private final String expectedToken;

    public LanDiscovery() {
        this("");
    }

    /**
     * @param expectedToken 已配对时传 token，只接受带同样 token 的广播；传空表示不校验。
     */
    public LanDiscovery(String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * 阻塞式搜索，返回第一个匹配的手机，超时返回 null。
     * 必须在子线程调用。
     */
    public LanDevice search(long timeoutMs) {
        if (LanBeacon.isListening()) {
            LanDevice device = LanBeacon.waitFor(expectedToken, timeoutMs, () -> cancelled);
            if (device != null) {
                Log.i(TAG, "发现设备 " + device);
                return device;
            }
            return null;
        }
        return searchWithOwnSocket(timeoutMs);
    }

    private LanDevice searchWithOwnSocket(long timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(LanConstants.DISCOVERY_PORT));
            socket.setSoTimeout(1000);

            byte[] buffer = new byte[2048];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!cancelled && System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                LanDevice device = parse(json, packet.getAddress().getHostAddress());
                if (device != null) {
                    Log.i(TAG, "发现设备 " + device);
                    return device;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "搜索失败: " + e.getMessage());
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
        return null;
    }

    private LanDevice parse(String json, String host) {
        try {
            JSONObject object = new JSONObject(json);
            if (!LanConstants.SERVICE_NAME.equals(object.optString("service"))) {
                return null;
            }
            if (!expectedToken.isEmpty() && !expectedToken.equals(object.optString("token"))) {
                // 已配对其他手机，忽略陌生广播
                return null;
            }
            int port = object.optInt("port", LanConstants.DEFAULT_HTTP_PORT);
            String name = object.optString("name", host);
            return new LanDevice(host, port, name);
        } catch (Exception e) {
            return null;
        }
    }
}
