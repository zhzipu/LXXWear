package cn.wearbbs.music.lan;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 取手表在 Wi-Fi 上的 IPv4 地址，写进配对二维码。
 */
public final class LanAddress {

    private LanAddress() {
    }

    /**
     * 只认 wlan 网卡（蓝牙、蜂窝、虚拟网卡都不算），没有 Wi-Fi 时返回 null。
     */
    public static String localWifiIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName();
                if (name == null || !name.startsWith("wlan")) {
                    continue;
                }
                for (InterfaceAddress address : nif.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) {
                        return ip.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
