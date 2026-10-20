package cn.wearbbs.music.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import cn.wearbbs.music.R;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlayOrder;
import cn.wearbbs.music.ui.LanActivity;
import cn.wearbbs.music.ui.PlayListActivity;
import cn.wearbbs.music.util.ToastUtil;

/**
 * 控制台页：音量、播放模式、播放列表入口。
 */
public class ConsoleFragment extends Fragment {

    private AudioManager audioManager;
    private int maxVolume;
    private ImageView ivRepeat;

    /** 在播放器页点了封面换顺序时，这页的图标也要跟着换。 */
    private final BroadcastReceiver orderReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRepeatIcon();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_console, container, false);

        audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        }

        ivRepeat = view.findViewById(R.id.iv_repeat);
        ivRepeat.setOnClickListener(v -> toggleOrder());
        view.findViewById(R.id.iv_voiceUp).setOnClickListener(v -> changeVolume(1));
        view.findViewById(R.id.iv_voiceDown).setOnClickListener(v -> changeVolume(-1));
        view.findViewById(R.id.iv_playlist).setOnClickListener(
                v -> startActivity(new Intent(requireContext(), PlayListActivity.class)));
        view.findViewById(R.id.iv_lan).setOnClickListener(
                v -> startActivity(new Intent(requireContext(), LanActivity.class)));

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(orderReceiver,
                new IntentFilter(PlayerActions.ACTION_ORDER_CHANGED));

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRepeatIcon();
    }

    @Override
    public void onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(orderReceiver);
        super.onDestroyView();
    }

    private void toggleOrder() {
        // 和点封面走同一条路：四档依次轮换，时间栏也会闪一下模式名
        PlayerFragment.cyclePlayOrder(requireContext());
        updateRepeatIcon();
    }

    private void updateRepeatIcon() {
        if (ivRepeat == null) {
            return;
        }
        ivRepeat.setImageResource(PlayOrder.iconRes(PlayerFragment.getPlayOrder()));
    }

    private void changeVolume(int step) {
        if (audioManager == null) {
            return;
        }
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = Math.max(0, Math.min(maxVolume, current + step));
        if (target == current) {
            ToastUtil.show(requireActivity(), getString(
                    current >= maxVolume ? R.string.volume_max : R.string.volume_min));
            return;
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        ToastUtil.show(requireActivity(),
                getString(R.string.volume_tip_format, target, maxVolume));
    }
}
