package cn.wearbbs.music.util;

import android.app.Activity;
import android.widget.Toast;

public class ToastUtil {

    public static void show(Activity activity, String text) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(
                () -> Toast.makeText(activity.getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }
}
