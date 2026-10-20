package cn.wearbbs.music.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import cn.wearbbs.music.R;
import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlaylistProvider;
import me.wcy.lrcview.LrcView;

/**
 * 歌词页：显示当前歌曲的 LRC，支持拖动跳转。
 */
public class LyricsFragment extends Fragment {

    private LrcView lrcView;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (lrcView == null) {
                return;
            }
            String action = intent.getAction();
            if (PlayerActions.ACTION_POSITION.equals(action)) {
                lrcView.updateTime(intent.getIntExtra(PlayerActions.EXTRA_POSITION, 0));
            } else if (PlayerActions.ACTION_SONG_CHANGED.equals(action)) {
                loadLyric();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_lyrics, container, false);
        lrcView = view.findViewById(R.id.lv_main);
        lrcView.setDraggable(true, (lrc, time) -> {
            PlayerFragment.seekTo((int) time);
            return true;
        });
        loadLyric();

        IntentFilter filter = new IntentFilter();
        filter.addAction(PlayerActions.ACTION_POSITION);
        filter.addAction(PlayerActions.ACTION_SONG_CHANGED);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver, filter);

        return view;
    }

    @Override
    public void onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver);
        lrcView = null;
        super.onDestroyView();
    }

    private void loadLyric() {
        if (lrcView == null) {
            return;
        }
        Song song = PlaylistProvider.getCurrent();
        String lyric = song == null ? null : song.getLyric();
        if (TextUtils.isEmpty(lyric)) {
            lrcView.loadLrc("[00:00.00]" + getString(R.string.no_lyrics));
        } else {
            lrcView.loadLrc(lyric);
        }
        lrcView.updateTime(0);
    }
}
