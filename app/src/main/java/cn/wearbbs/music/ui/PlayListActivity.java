package cn.wearbbs.music.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import cn.wearbbs.music.R;
import cn.wearbbs.music.adapter.MusicAdapter;
import cn.wearbbs.music.player.PlayerActions;
import cn.wearbbs.music.player.PlaylistProvider;

/**
 * 播放列表页：点一首直接切歌并返回播放器页。
 */
public class PlayListActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);

        ((TextView) findViewById(R.id.tv_title)).setText(R.string.playList);

        RecyclerView rvMain = findViewById(R.id.rv_main);
        View emptyView = findViewById(R.id.tv_empty);
        rvMain.setLayoutManager(new LinearLayoutManager(this));
        rvMain.setAdapter(new MusicAdapter(this, PlaylistProvider.getSongs(), this::select));

        boolean empty = PlaylistProvider.isEmpty();
        rvMain.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void select(int position) {
        PlaylistProvider.setCurrentIndex(position);
        LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent(PlayerActions.ACTION_SELECT_INDEX)
                        .putExtra(PlayerActions.EXTRA_INDEX, position));
        finish();
    }

    public void onClick(View view) {
        finish();
    }
}
