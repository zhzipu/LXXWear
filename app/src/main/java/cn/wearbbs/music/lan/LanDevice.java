package cn.wearbbs.music.lan;

import java.util.Locale;

/**
 * 局域网里发现的一台手机。
 */
public class LanDevice {

    public final String host;
    public final int port;
    public final String name;

    public LanDevice(String host, int port, String name) {
        this.host = host;
        this.port = port;
        this.name = name;
    }

    public String baseUrl() {
        return String.format(Locale.US, "http://%s:%d", host, port);
    }

    @Override
    public String toString() {
        return name + " (" + host + ":" + port + ")";
    }
}
