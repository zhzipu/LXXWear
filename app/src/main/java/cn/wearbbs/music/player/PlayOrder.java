package cn.wearbbs.music.player;

import cn.wearbbs.music.R;
import cn.wearbbs.music.service.MusicPlayerService;
import cn.wearbbs.music.util.SharedPreferencesUtil;

/**
 * 播放顺序：点封面（或控制台那个按钮）在这四种之间轮换。
 * <p>
 * 轮换顺序按产品要求固定为：单曲循环 → 列表循环 → 列表随机 → 列表顺序 → 单曲循环…
 * 模式记在本地，下次启动沿用上次选的那档。
 */
public final class PlayOrder {

    private static final String KEY_ORDER = "play_order";

    /** 点一次换下一个，顺序就是用户看到的那四档。 */
    private static final int[] CYCLE = {
            MusicPlayerService.ORDER_REPEAT_ONE,
            MusicPlayerService.ORDER_REPEAT_ALL,
            MusicPlayerService.ORDER_SHUFFLE,
            MusicPlayerService.ORDER_SEQUENCE,
    };

    /** -1 表示还没从本地读过。 */
    private static int cached = -1;

    private PlayOrder() {
    }

    /** 当前模式，第一次调用时从本地读。 */
    public static int get() {
        if (cached < 0) {
            cached = SharedPreferencesUtil.getInt(KEY_ORDER, MusicPlayerService.ORDER_SEQUENCE);
        }
        return cached;
    }

    public static void set(int order) {
        cached = order;
        SharedPreferencesUtil.putInt(KEY_ORDER, order);
    }

    /** 下一个模式。当前值不认识时从单曲循环开始。 */
    public static int next(int current) {
        for (int i = 0; i < CYCLE.length; i++) {
            if (CYCLE[i] == current) {
                return CYCLE[(i + 1) % CYCLE.length];
            }
        }
        return CYCLE[0];
    }

    /** 时间栏上显示的名字。 */
    public static int labelRes(int order) {
        switch (order) {
            case MusicPlayerService.ORDER_REPEAT_ONE:
                return R.string.order_repeat_one;
            case MusicPlayerService.ORDER_REPEAT_ALL:
                return R.string.order_repeat_all;
            case MusicPlayerService.ORDER_SHUFFLE:
                return R.string.order_shuffle;
            default:
                return R.string.order_sequence;
        }
    }

    /** 控制台页那个按钮的图标。 */
    public static int iconRes(int order) {
        switch (order) {
            case MusicPlayerService.ORDER_REPEAT_ONE:
                return R.drawable.icon_play_repeat_one;
            case MusicPlayerService.ORDER_REPEAT_ALL:
                return R.drawable.icon_play_order;
            case MusicPlayerService.ORDER_SHUFFLE:
                return R.drawable.icon_play_shuffle;
            default:
                return R.drawable.icon_play_sequence;
        }
    }
}
