package cn.wearbbs.music.lan;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 手机端 HTTP 接口的最小客户端，只依赖 HttpURLConnection。
 */
public class LanClient {

    public interface DownloadListener {
        void onProgress(long downloaded, long total);

        boolean isCancelled();
    }

    private final LanDevice device;
    private final String token;

    public LanClient(LanDevice device) {
        this(device, "");
    }

    public LanClient(LanDevice device, String token) {
        this.device = device;
        this.token = token == null ? "" : token;
    }

    public LanDevice getDevice() {
        return device;
    }

    public boolean ping() {
        try {
            JSONObject object = getJson(LanConstants.PATH_PING);
            return LanConstants.SERVICE_NAME.equals(object.optString("service"));
        } catch (Exception e) {
            return false;
        }
    }

    public JSONObject getJson(String path) throws IOException {
        HttpURLConnection connection = open(path, "GET");
        try {
            int code = connection.getResponseCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
            try {
                return new JSONObject(readAll(connection.getInputStream()));
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("响应不是合法 JSON", e);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 告诉手机端「这首手表本地已经有完整文件了，这一轮不用准备」。
     * 老版本手机端没有这个接口（返回 404），忽略即可。
     */
    public void skip(String id) {
        try {
            post(LanConstants.PATH_SKIP + encode(id), "{}");
        } catch (IOException ignored) {
        }
    }

    /**
     * 一轮同步结束（正常完成或用户取消），把统计发给手机，
     * 手机据此结束任务、在界面上显示结果，不用用户再手动点停止。
     */
    public void finish(JSONObject payload) {
        try {
            post(LanConstants.PATH_FINISH, payload.toString());
        } catch (IOException ignored) {
        }
    }

    /** 发一个 JSON POST，忽略响应内容（服务端用 404 表示不支持该接口） */
    private void post(String path, String body) throws IOException {
        HttpURLConnection connection = open(path, "POST");
        try {
            connection.setDoOutput(true);
            byte[] data = body.getBytes("UTF-8");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(data);
            }
            int code = connection.getResponseCode();
            if (code == 404) {
                return;
            }
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
            // 读掉响应体，连接才能及时释放
            readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 读纯文本（歌词），404 时返回 null。
     */
    public String getTextOrNull(String path) throws IOException {
        HttpURLConnection connection = open(path, "GET");
        try {
            int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
            return readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 让手机端确保这首歌的音频已在本地（没有就先下载），返回 ready / preparing / failed。
     * 服务端不实现该接口（404）时按 ready 处理，直接走下载。
     */
    public String prepare(String id) throws IOException {
        HttpURLConnection connection = open(LanConstants.PATH_PREPARE + encode(id), "POST");
        try {
            connection.setDoOutput(true);
            connection.getOutputStream().close();
            int code = connection.getResponseCode();
            if (code == 404) {
                return "ready";
            }
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
            try {
                return new JSONObject(readAll(connection.getInputStream())).optString("state", "ready");
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("响应不是合法 JSON", e);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 下载一个文件到 target：先写 target.part，完整收完才改名。
     * <p>
     * 断点续传：上一次没传完的 .part 会留着，下次带 Range 从断点继续；
     * 服务端不支持 Range（回 200）时自动从头覆盖。
     */
    public void download(String path, File target, DownloadListener listener) throws IOException {
        File part = new File(target.getPath() + ".part");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        long existing = part.exists() ? part.length() : 0L;
        int attempt = 0;
        while (true) {
            attempt++;
            boolean append = false;
            long offset = 0L;
            long total = -1L;
            HttpURLConnection connection = open(path, "GET");
            try {
                if (existing > 0) {
                    connection.setRequestProperty("Range", "bytes=" + existing + "-");
                }
                int code = connection.getResponseCode();
                if (code == 206) {
                    offset = existing;
                    append = true;
                    total = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
                } else if (code == 416) {
                    // 本地残留比服务端文件还长（换过文件），丢掉重来
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    existing = 0L;
                    if (attempt < 2) {
                        continue;
                    }
                    throw new IOException("HTTP 416");
                } else if (code / 100 == 2) {
                    if (existing > 0) {
                        // 服务端没按 Range 返回，只能整份重下
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                    }
                    long length = parseLong(connection.getHeaderField("Content-Length"), -1L);
                    total = length > 0 ? length : -1L;
                } else {
                    throw new IOException("HTTP " + code);
                }

                long downloaded = offset;
                if (listener != null && offset > 0) {
                    // 续传时先报一次起点，界面别从 0 开始跳
                    listener.onProgress(downloaded, total);
                }
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(part, append)) {
                    byte[] buffer = new byte[16 * 1024];
                    int length;
                    while ((length = in.read(buffer)) != -1) {
                        if (listener != null && listener.isCancelled()) {
                            throw new IOException("已取消");
                        }
                        out.write(buffer, 0, length);
                        downloaded += length;
                        if (listener != null) {
                            listener.onProgress(downloaded, total);
                        }
                    }
                }
            } finally {
                connection.disconnect();
            }
            // 传完核对一下长度：断点续传时如果服务端换了文件，长度会对不上
            if (total > 0 && part.length() != total) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("文件长度不符（" + part.length() + "/" + total + "）");
            }
            break;
        }

        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖旧文件");
        }
        if (!part.renameTo(target)) {
            throw new IOException("保存文件失败");
        }
    }

    /** Content-Range: bytes 100-999/1000 → 1000 */
    private static long parseContentRangeTotal(String value) {
        if (value == null) {
            return -1L;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0) {
            return -1L;
        }
        return parseLong(value.substring(slash + 1), -1L);
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        URL url = new URL(device.baseUrl() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(LanConstants.CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(LanConstants.READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "*/*");
        if (!token.isEmpty()) {
            connection.setRequestProperty("X-LX-Token", token);
        }
        connection.setUseCaches(false);
        return connection;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
            return out.toString("UTF-8");
        }
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
