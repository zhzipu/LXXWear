package cn.wearbbs.music.util;

import android.content.Context;
import android.content.SharedPreferences;

import cn.wearbbs.music.application.MainApplication;

public class SharedPreferencesUtil {

    private static final String FILE_NAME = "cn.wearbbs.music_preferences";

    private static SharedPreferences prefs() {
        return MainApplication.getContext().getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static void putString(String key, String value) {
        prefs().edit().putString(key, value).apply();
    }

    public static void putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public static void putInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
    }

    public static Boolean getBoolean(String key, Boolean defValue) {
        return prefs().getBoolean(key, defValue);
    }

    public static String getString(String key, String defValue) {
        return prefs().getString(key, defValue);
    }

    public static int getInt(String key, int defValue) {
        return prefs().getInt(key, defValue);
    }

    public static void remove(String key) {
        prefs().edit().remove(key).apply();
    }
}
