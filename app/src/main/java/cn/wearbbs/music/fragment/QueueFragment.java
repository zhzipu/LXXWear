package cn.wearbbs.music.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import cn.wearbbs.music.R;
import cn.wearbbs.music.adapter.MusicAdapter;
import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlaylistProvider;

/**
 * 播放列表页（在播放器右边，从播放器往左滑进来）：就是播放器当前这一队列，
 * 正在播的那首用高亮色标出来。
 * <p>
 * 和左边的歌单页不同：这里不分组、不换列表，点哪首就把当前索引挪到哪首并立刻播，
 * 然后回到播放器页。队列本身由歌单页决定（点歌单里的歌会整表替换）。
 */
public class QueueFragment extends Fragment {

    private RecyclerView rvSongs;
    private TextView tvCount;
    private TextView tvEmpty;

    private MusicAdapter adapter;
    /** 自己存一份快照：同步任务整表替换时列表对象会换，直接拿 PlaylistProvider 的引用会失效 */
    private final List<Song> songs = new ArrayList<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (PlayerActions.ACTION_SONG_CHANGED.equals(action)) {
                // 只是换了当前这首：刷新高亮，不用整表重读
                notifyCurrent();
            } else {
                // 播放列表整体被换了（点歌单、同步补歌、清空）
                reload();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_queue, container, false);
        rvSongs = view.findViewById(R.id.rv_songs);
        tvCount = view.findViewById(R.id.tv_count);
        tvEmpty = view.findViewById(R.id.tv_empty);

        rvSongs.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new MusicAdapter(requireContext(), songs, this::onSongClick);
        rvSongs.setAdapter(adapter);

        IntentFilter filter = new IntentFilter();
        filter.addAction(PlayerActions.ACTION_PLAYLIST_CHANGED);
        filter.addAction(PlayerActions.ACTION_LIBRARY_CHANGED);
        filter.addAction(PlayerActions.ACTION_SONG_CHANGED);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver, filter);

        reload();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 滑到这一页时对一次表，并把当前这首滚进可视区
        reload();
        scrollToCurrent();
    }

    @Override
    public void onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver);
        super.onDestroyView();
    }

    private void reload() {
        if (rvSongs == null) {
            return;
        }
        List<Song> latest = PlaylistProvider.getSongs();
        songs.clear();
        songs.addAll(latest);
        adapter.notifyDataSetChanged();

        boolean empty = songs.isEmpty();
        rvSongs.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvCount.setText(empty ? getString(R.string.playList)
                : getString(R.string.queue_count, songs.size()));
    }

    private void notifyCurrent() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void scrollToCurrent() {
        if (rvSongs == null || songs.isEmpty()) {
            return;
        }
        int index = PlaylistProvider.getCurrentIndex();
        if (index < 0 || index >= songs.size()) {
            return;
        }
        // 放到中间偏上：前面的歌还能看到一点，知道自己在队列里的位置
        rvSongs.post(() -> {
            LinearLayoutManager manager = (LinearLayoutManager) rvSongs.getLayoutManager();
            if (manager != null) {
                manager.scrollToPositionWithOffset(index, rvSongs.getHeight() / 3);
            }
        });
    }

    private void onSongClick(int position) {
        if (position < 0 || position >= songs.size()) {
            return;
        }
        PlaylistProvider.setCurrentIndex(position);
        // 播放器页收到这条广播会切到这首并开始播；主界面同时把页面拨回播放器列
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(
                new Intent(PlayerActions.ACTION_SELECT_INDEX)
                        .putExtra(PlayerActions.EXTRA_INDEX, PlaylistProvider.getCurrentIndex()));
        notifyCurrent();
    }
}
