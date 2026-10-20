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
import cn.wearbbs.music.lan.LanLibrary;
import cn.wearbbs.music.lan.LanPlaylist;
import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlaylistProvider;

/**
 * 歌单页（播放器左边那页，从播放器往右滑进来）：分两层。
 * <ol>
 *   <li>第一层列手机同步下来的各个歌单（名称 + 曲目数 + 首曲封面）；</li>
 *   <li>点开某个歌单，第二层列它的歌，顶栏点一下退回第一层。</li>
 * </ol>
 * 点歌 = 用这个歌单整体替换当前播放列表，并从点的那首开始播。
 */
public class SongListFragment extends Fragment {

    /** 一首都没同步过时，把播放列表本身当成一个歌单显示 */
    private static final String LOCAL_GROUP_ID = "lan-local";
    /** 切层后这段时间内的歌曲点击一律忽略，防止同一次点击被重复派发 */
    private static final long LEVEL_SWITCH_GUARD_MS = 400L;

    private RecyclerView rvSongs;
    private TextView tvCount;
    private TextView tvEmpty;

    private MusicAdapter playlistAdapter;
    private MusicAdapter songAdapter;

    /** 第一层：歌单列表 */
    private final List<LanPlaylist> playlists = new ArrayList<>();
    /** 第一层展示用，用 Song 承载（name=歌单名，artist=曲目数，coverUrl=首曲封面） */
    private final List<Song> playlistItems = new ArrayList<>();
    /** 与 playlistItems 一一对应的歌单 id */
    private final List<String> playlistKeys = new ArrayList<>();
    /** 第二层：当前歌单里的歌 */
    private final List<Song> songs = new ArrayList<>();

    /** null = 停在歌单列表这一层 */
    private String openPlaylistId = null;

    /**
     * 刚切过层级的时间点。换 adapter 时 RecyclerView 可能把同一次点击再派发给新列表，
     * 导致「点歌单」顺带把第一首播了，所以切换后短暂忽略歌曲点击。
     */
    private long levelSwitchAt = 0L;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (PlayerActions.ACTION_PLAYLIST_CHANGED.equals(action)
                    || PlayerActions.ACTION_LIBRARY_CHANGED.equals(action)) {
                // 同步任务替换了曲库：重新读一遍；开着的歌单被删了会自动退回第一层
                reload();
            } else if (PlayerActions.ACTION_SONG_CHANGED.equals(action)) {
                // 只是换了当前播放的歌：刷新高亮就行
                notifyCurrent();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_song_list, container, false);
        rvSongs = view.findViewById(R.id.rv_songs);
        tvCount = view.findViewById(R.id.tv_count);
        tvEmpty = view.findViewById(R.id.tv_empty);

        rvSongs.setLayoutManager(new LinearLayoutManager(requireContext()));
        playlistAdapter = new MusicAdapter(requireContext(), playlistItems, this::onPlaylistClick);
        songAdapter = new MusicAdapter(requireContext(), songs, this::onSongClick);
        rvSongs.setAdapter(playlistAdapter);

        // 顶栏点了就退回歌单列表（第二层才有反应，见 render）
        tvCount.setOnClickListener(v -> goBackToPlaylists());

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
        // 从同步页回来、或者别的页面改了播放列表，进页面时对一次
        reload();
    }

    @Override
    public void onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver);
        super.onDestroyView();
    }

    // ------------------------------------------------------------ 数据

    private void reload() {
        if (rvSongs == null) {
            return;
        }
        playlists.clear();
        playlists.addAll(readPlaylists());
        if (openPlaylistId != null && findPlaylist(openPlaylistId) == null) {
            // 手机端取消勾选了这个歌单，曲库已经把它删干净了
            openPlaylistId = null;
        }
        render();
    }

    private List<LanPlaylist> readPlaylists() {
        List<LanPlaylist> list = new ArrayList<>(LanLibrary.readPlaylists(requireContext()));
        if (list.isEmpty()) {
            List<Song> local = PlaylistProvider.getSongs();
            if (!local.isEmpty()) {
                LanPlaylist group = new LanPlaylist(LOCAL_GROUP_ID, getString(R.string.song_list_local));
                for (int i = 0; i < local.size(); i++) {
                    String id = local.get(i).getId();
                    group.songIds.add(TextUtils.isEmpty(id) ? "pos-" + i : id);
                }
                list.add(group);
            }
        }
        return list;
    }

    private LanPlaylist findPlaylist(String id) {
        for (LanPlaylist item : playlists) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** 某个歌单实际能播的歌；文件缺失的曲目在 LanLibrary 里已被跳过。 */
    private List<Song> songsOf(LanPlaylist playlist) {
        if (LOCAL_GROUP_ID.equals(playlist.id)) {
            return new ArrayList<>(PlaylistProvider.getSongs());
        }
        return LanLibrary.loadSongs(requireContext(), playlist);
    }

    private void render() {
        if (openPlaylistId == null) {
            playlistItems.clear();
            playlistKeys.clear();
            for (LanPlaylist playlist : playlists) {
                List<Song> list = songsOf(playlist);
                Song item = new Song(playlist.name,
                        getString(R.string.playlist_song_count, list.size()), null);
                item.setId("pl:" + playlist.id);
                if (!list.isEmpty()) {
                    item.setCoverUrl(list.get(0).getCoverUrl());
                }
                playlistItems.add(item);
                playlistKeys.add(playlist.id);
            }
            songs.clear();
            rvSongs.setAdapter(playlistAdapter);
            playlistAdapter.notifyDataSetChanged();

            boolean empty = playlistItems.isEmpty();
            rvSongs.setVisibility(empty ? View.GONE : View.VISIBLE);
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            tvEmpty.setText(R.string.song_list_empty);
            tvCount.setText(empty
                    ? getString(R.string.song_list)
                    : getString(R.string.playlist_group_count, playlistItems.size()));
            return;
        }

        LanPlaylist playlist = findPlaylist(openPlaylistId);
        songs.clear();
        if (playlist != null) {
            songs.addAll(songsOf(playlist));
        }
        rvSongs.setAdapter(songAdapter);
        songAdapter.notifyDataSetChanged();

        boolean empty = songs.isEmpty();
        rvSongs.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvEmpty.setText(R.string.playlist_songs_empty);
        String name = playlist == null ? getString(R.string.song_list) : playlist.name;
        tvCount.setText(getString(R.string.playlist_songs_header, name, songs.size()));
    }

    private void notifyCurrent() {
        if (playlistAdapter != null) {
            playlistAdapter.notifyDataSetChanged();
        }
        if (songAdapter != null) {
            songAdapter.notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------ 交互

    /**
     * 从歌曲列表退回歌单列表（顶部时间栏、页面顶栏都调它）。
     *
     * @return 已经在歌单列表这一层时返回 false
     */
    public boolean goBackToPlaylists() {
        if (openPlaylistId == null) {
            return false;
        }
        openPlaylistId = null;
        levelSwitchAt = System.currentTimeMillis();
        if (rvSongs != null) {
            rvSongs.post(this::reload);
        }
        return true;
    }

    private void onPlaylistClick(int position) {
        if (position < 0 || position >= playlistKeys.size()) {
            return;
        }
        openPlaylistId = playlistKeys.get(position);
        levelSwitchAt = System.currentTimeMillis();
        // 等这次点击事件走完再换 adapter：当场换的话，同一次点击会被新列表当成「点了第一首」
        if (rvSongs != null) {
            rvSongs.post(this::render);
        }
    }

    private void onSongClick(int position) {
        if (System.currentTimeMillis() - levelSwitchAt < LEVEL_SWITCH_GUARD_MS) {
            return;
        }
        if (position < 0 || position >= songs.size()) {
            return;
        }
        String id = songs.get(position).getId();
        String keepId = TextUtils.isEmpty(id) ? null : id;
        // 用整份歌单替换播放列表，并把当前索引落在点的那首上
        PlaylistProvider.replaceSongs(new ArrayList<>(songs), keepId);
        if (keepId == null) {
            // assets 里的示例歌没有 id，只能按下标定位
            PlaylistProvider.setCurrentIndex(position);
        }

        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(requireContext());
        // 先让播放器把地址表换成新歌单（不发声），再按索引切过去开始播
        manager.sendBroadcast(new Intent(PlayerActions.ACTION_PLAYLIST_CHANGED));
        manager.sendBroadcast(new Intent(PlayerActions.ACTION_SELECT_INDEX)
                .putExtra(PlayerActions.EXTRA_INDEX, PlaylistProvider.getCurrentIndex()));
    }
}
