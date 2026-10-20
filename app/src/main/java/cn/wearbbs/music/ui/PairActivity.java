package cn.wearbbs.music.ui;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

import cn.wearbbs.music.R;
import cn.wearbbs.music.lan.LanAddress;
import cn.wearbbs.music.lan.LanAutoSync;
import cn.wearbbs.music.lan.LanPairServer;
import cn.wearbbs.music.lan.LanPairing;

/**
 * 配对页：手表把「地址 + 端口 + 一次性配对码」编码成二维码，手机扫码后连过来换取 token。
 * 配对成功后双方绑定，后续自动同步只认这台手机。
 */
public class PairActivity extends AppCompatActivity {

    private static final int QR_PIXELS = 480;

    private ImageView ivQr;
    private TextView tvStatus;
    private TextView tvManual;
    private Button btnUnpair;

    private LanPairServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair);

        ivQr = findViewById(R.id.iv_qr);
        tvStatus = findViewById(R.id.tv_status);
        tvManual = findViewById(R.id.tv_manual);
        btnUnpair = findViewById(R.id.btn_unpair);

        findViewById(R.id.iv_back).setOnClickListener(v -> finish());
        // 点整条标题栏也当返回（二级页的统一手势）
        findViewById(R.id.main_title).setOnClickListener(v -> finish());
        btnUnpair.setOnClickListener(v -> {
            LanPairing.clear(this);
            showPairingCode();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 服务还在就不要重建，重建会换一个新配对码，手机刚扫到的那张就失效了
        if (server != null) {
            return;
        }
        // 首次进入、或者上次没连 Wi-Fi 没能出码，这里补一次
        showPairingCode();
    }

    @Override
    protected void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void showPairingCode() {
        stopServer();
        btnUnpair.setVisibility(View.GONE);
        ivQr.setVisibility(View.VISIBLE);

        if (LanPairing.isPaired(this)) {
            ivQr.setVisibility(View.GONE);
            tvStatus.setText(getString(R.string.pair_already_format, LanPairing.getDeviceName(this)));
            tvManual.setText(R.string.pair_unpair_tip);
            btnUnpair.setVisibility(View.VISIBLE);
            return;
        }

        String ip = LanAddress.localWifiIp();
        if (ip == null) {
            ivQr.setVisibility(View.GONE);
            tvStatus.setText(R.string.pair_wifi_required);
            tvManual.setText("");
            return;
        }

        final String code = LanPairing.newCode();
        server = new LanPairServer(Build.MODEL, code, (deviceName, token, peer) -> runOnUiThread(() -> {
            LanPairing.save(this, token, deviceName);
            stopServer();
            ivQr.setVisibility(View.GONE);
            btnUnpair.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.pair_success_format, deviceName));
            tvManual.setText(peer);
            // 配对完成立刻对齐一次歌单
            LanAutoSync.requestNow();
        }));

        int port = server.start();
        if (port <= 0) {
            stopServer();
            ivQr.setVisibility(View.GONE);
            tvStatus.setText(R.string.pair_server_failed);
            tvManual.setText("");
            return;
        }

        String content = "lxnx://p?h=" + ip + "&p=" + port + "&c=" + code;
        Bitmap qr = createQrCode(content);
        if (qr == null) {
            ivQr.setVisibility(View.GONE);
            tvStatus.setText(R.string.pair_qr_failed);
            return;
        }
        ivQr.setImageBitmap(qr);
        tvStatus.setText(R.string.pair_tip);
        tvManual.setText(ip + ":" + port + " · " + code);
    }

    private Bitmap createQrCode(String content) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, QR_PIXELS, QR_PIXELS, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
