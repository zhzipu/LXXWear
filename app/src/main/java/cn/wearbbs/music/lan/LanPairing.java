package cn.wearbbs.music.lan;

import android.content.Context;
import android.text.TextUtils;

import java.security.SecureRandom;
import java.util.Locale;

import cn.wearbbs.music.util.SharedPreferencesUtil;

/**
 * 配对信息：手表与哪台手机绑定了，以及双方约定的 token。
 * 配对后手表只接受带这个 token 的广播与请求，避免连到别人家的手机。
 */
public final class LanPairing {

    private static final String KEY_TOKEN = "lan_pair_token";
    private static final String KEY_NAME = "lan_pair_name";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LanPairing() {
    }

    public static boolean isPaired(Context context) {
        return !getToken(context).isEmpty();
    }

    public static String getToken(Context context) {
        return SharedPreferencesUtil.getString(KEY_TOKEN, "");
    }

    public static String getDeviceName(Context context) {
        return SharedPreferencesUtil.getString(KEY_NAME, "");
    }

    public static void save(Context context, String token, String deviceName) {
        SharedPreferencesUtil.putString(KEY_TOKEN, token == null ? "" : token);
        SharedPreferencesUtil.putString(KEY_NAME, deviceName == null ? "" : deviceName);
    }

    public static void clear(Context context) {
        SharedPreferencesUtil.remove(KEY_TOKEN);
        SharedPreferencesUtil.remove(KEY_NAME);
    }

    /** 6 位数字配对码，显示在二维码旁边，防止同网段的其它设备乱配对。 */
    public static String newCode() {
        return String.format(Locale.US, "%06d", RANDOM.nextInt(1000000));
    }

    /** 32 位十六进制 token。 */
    public static String newToken() {
        StringBuilder builder = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            builder.append(HEX[RANDOM.nextInt(HEX.length)]);
        }
        return builder.toString();
    }

    public static boolean matches(Context context, String token) {
        String local = getToken(context);
        return !local.isEmpty() && !TextUtils.isEmpty(token) && local.equals(token);
    }
}
