package cn.wearbbs.music.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import cn.wearbbs.music.R;
import cn.wearbbs.music.lan.LanLibrary;
import cn.wearbbs.music.lan.LanPairing;
import cn.wearbbs.music.lan.LanSyncManager;
import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlayerActions;

/**
 * 局域网同步页：搜索手机 → 对齐手机端选中的歌单 → 回到播放器。
 * 平时不需要进来，应用启动、Wi-Fi 变化时后台会自动同步；这里用于手动重试与查看状态。
 */
public class LanActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvDetail;
    private TextView tvLibrary;
    private ProgressBar pbSync;
    private Button btnAction;

    private LanSyncManager syncManager;
    private boolean succeeded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lan);

        tvStatus = findViewById(R.id.tv_status);
        tvDetail = findViewById(R.id.tv_detail);
        tvLibrary = findViewById(R.id.tv_library);
        pbSync = findViewById(R.id.pb_sync);
        btnAction = findViewById(R.id.btn_action);

        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        // 点整条标题栏也当返回（二级页的统一手势）
        findViewById(R.id.main_title).setOnClickListener(v -> finish());
        findViewById(R.id.tv_pair).setOnClickListener(
                v -> startActivity(new Intent(this, PairActivity.class)));
        btnAction.setOnClickListener(v -> {
            if (syncManager != null && syncManager.isRunning()) {
                syncManager.cancel();
                tvStatus.setText(R.string.lan_cancelled);
                tvDetail.setText("");
                btnAction.setText(R.string.lan_retry);
            } else {
                startSync();
            }
        });

        updateLibrarySummary();
        LanSyncManager ongoing = LanSyncManager.running();
        if (ongoing != null) {
            // 同步还在后台跑（可能是自动同步，也可能是刚才退出去听歌时没停的那个）：接着显示进度
            syncManager = ongoing;
            pbSync.setProgress(0);
            btnAction.setText(R.string.lan_stop);
            ongoing.attach(buildCallback());
        } else {
            startSync();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLibrarySummary();
    }

    @Override
    protected void onDestroy() {
        // 注意：这里只断开界面，不取消同步 —— 用户退出去先听已经传完的歌时，
        // 后台还要继续往手表里补剩下的。
        if (syncManager != null) {
            syncManager.detach();
        }
        super.onDestroy();
    }

    /** 界面还在才更新，避免同步回调打到已经销毁的页面上。 */
    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void updateLibrarySummary() {
        JSONObject index = LanLibrary.readIndex(this);
        String playlistName = index.optString("playlistName", "");
        List<Song> songs = LanLibrary.load(this);
        String summary = getString(R.string.lan_library_format, songs.size(),
                formatSize(LanLibrary.sizeOnDisk(this)));
        StringBuilder builder = new StringBuilder();
        if (LanPairing.isPaired(this)) {
            builder.append(getString(R.string.lan_paired_format, LanPairing.getDeviceName(this)))
                    .append(" · ");
        }
        if (!playlistName.isEmpty()) {
            builder.append(playlistName).append(" · ");
        }
        builder.append(summary);
        tvLibrary.setText(builder.toString());
    }

    private void startSync() {
        if (syncManager != null && syncManager.isRunning()) {
            return;
        }
        succeeded = false;
        pbSync.setProgress(0);
        tvStatus.setText(R.string.lan_searching);
        tvDetail.setText(R.string.lan_same_wifi_tip);
        btnAction.setText(R.string.lan_stop);

        syncManager = new LanSyncManager();
        syncManager.start(this, LanSyncManager.Mode.FULL, buildCallback());
    }

    private LanSyncManager.Callback buildCallback() {
        return new LanSyncManager.Callback() {
            @Override
            public void onStatus(final String status, final String detail) {
                runOnUiThread(() -> {
                    if (!alive()) {
                        return;
                    }
                    tvStatus.setText(status);
                    tvDetail.setText(detail == null ? "" : detail);
                });
            }

            @Override
            public void onProgress(final int percent) {
                runOnUiThread(() -> {
                    if (!alive()) {
                        return;
                    }
                    pbSync.setProgress(percent);
                });
            }

            @Override
            public void onSongReady(Song song, int finished, int total) {
                runOnUiThread(() -> {
                    if (!alive()) {
                        return;
                    }
                    updateLibrarySummary();
                });
            }

            @Override
            public void onFinished(final boolean success, final String message) {
                runOnUiThread(() -> {
                    if (!alive()) {
                        return;
                    }
                    tvStatus.setText(message);
                    tvDetail.setText(success ? getString(R.string.lan_finished_tip) : "");
                    updateLibrarySummary();
                    if (success) {
                        pbSync.setProgress(100);
                        succeeded = true;
                        btnAction.setText(R.string.lan_open_player);
                        btnAction.setOnClickListener(v -> openPlayer());
                    } else {
                        btnAction.setText(R.string.lan_retry);
                        btnAction.setOnClickListener(v -> startSync());
                    }
                });
            }
        };
    }

    private void openPlayer() {
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(PlayerActions.ACTION_SHOW_PLAYER));
        finish();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
