package cn.wearbbs.music.lan;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 配对用的极简 HTTP 服务，只在「配对手机」页打开时运行。
 * 手机扫码后 POST /pair（带二维码里的配对码）即可换取 token。
 */
public class LanPairServer {

    private static final String TAG = "LanPairServer";
    public static final String SERVICE_NAME = "lxnx-pair";
    public static final int PREFERRED_PORT = 8688;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 256 * 1024;

    public interface Listener {
        void onPaired(String deviceName, String token, String peerAddress);
    }

    private final String watchName;
    private final String code;
    private final Listener listener;

    private ServerSocket serverSocket;
    private Thread thread;
    private volatile boolean running;

    public LanPairServer(String watchName, String code, Listener listener) {
        this.watchName = watchName;
        this.code = code;
        this.listener = listener;
    }

    /**
     * 启动并返回实际监听端口，失败返回 -1。
     */
    public int start() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            try {
                serverSocket.bind(new InetSocketAddress(PREFERRED_PORT));
            } catch (Exception busy) {
                // 8688 被占用就换个随机端口，端口会写进二维码里，不影响使用
                serverSocket.bind(new InetSocketAddress(0));
            }
            serverSocket.setSoTimeout(1000);
        } catch (Exception e) {
            Log.w(TAG, "启动配对服务失败: " + e.getMessage());
            closeQuietly();
            return -1;
        }

        running = true;
        thread = new Thread(this::loop, "lan-pair");
        thread.start();
        return serverSocket.getLocalPort();
    }

    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public void stop() {
        running = false;
        closeQuietly();
    }

    private void loop() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                handle(socket);
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "配对请求处理失败: " + e.getMessage());
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void handle(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        InputStream in = socket.getInputStream();

        byte[] headBytes = readHead(in);
        if (headBytes == null) {
            return;
        }
        String head = new String(headBytes, StandardCharsets.UTF_8);

        int lineEnd = head.indexOf("\r\n");
        String requestLine = lineEnd >= 0 ? head.substring(0, lineEnd) : head;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return;
        }
        String method = parts[0];
        String path = parts[1];

        Map<String, String> headers = new HashMap<>();
        int cursor = lineEnd < 0 ? head.length() : lineEnd + 2;
        while (cursor < head.length()) {
            int next = head.indexOf("\r\n", cursor);
            if (next < 0) {
                break;
            }
            String line = head.substring(cursor, next);
            cursor = next + 2;
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }

        // 注意：body 必须按「字节」读。Content-Length 是字节数，
        // 之前用 BufferedReader 按「字符」凑长度，body 里带中文时永远凑不满，
        // 会一直阻塞到 SoTimeout 然后把连接直接断掉，客户端只能看到「连不上」。
        String body = new String(readBody(in, headers), StandardCharsets.UTF_8);

        // 任何异常都要给客户端一个响应，否则对方只能等到超时、报「连不上」
        try {
            dispatch(socket, method, path, body);
        } catch (Exception e) {
            Log.w(TAG, "处理请求失败: " + e);
            try {
                send(socket, 500, "{\"ok\":false,\"error\":\"server error\"}");
            } catch (Exception ignored) {
            }
        }
    }

    private void dispatch(Socket socket, String method, String path, String body) throws Exception {
        if ("GET".equalsIgnoreCase(method) && "/ping".equals(path)) {
            JSONObject payload = new JSONObject();
            payload.put("service", SERVICE_NAME);
            payload.put("name", watchName);
            send(socket, 200, payload.toString());
            return;
        }
        if ("GET".equalsIgnoreCase(method) && "/pair/info".equals(path)) {
            JSONObject payload = new JSONObject();
            payload.put("service", SERVICE_NAME);
            payload.put("name", watchName);
            payload.put("port", getPort());
            payload.put("code", code);
            send(socket, 200, payload.toString());
            return;
        }
        if ("POST".equalsIgnoreCase(method) && "/pair".equals(path)) {
            handlePair(socket, body);
            return;
        }
        send(socket, 404, "{\"ok\":false,\"error\":\"not found\"}");
    }

    private void handlePair(Socket socket, String body) throws Exception {
        JSONObject request;
        try {
            request = new JSONObject(body);
        } catch (Exception e) {
            send(socket, 400, "{\"ok\":false,\"error\":\"bad json\"}");
            return;
        }
        String submitted = request.optString("code", "");
        if (!code.equals(submitted)) {
            send(socket, 403, "{\"ok\":false,\"error\":\"wrong code\"}");
            return;
        }
        String deviceName = request.optString("deviceName", "手机");
        // token 的权威来源是手机：手机重装后自己的 token 会变，如果这里自己另生成一个，
        // 手机存的与手表存的就会永久错位（表现为配对成功但同步一直拉不动）。
        String clientToken = request.optString("token", "").trim();
        String token = clientToken.matches("[0-9a-fA-F]{16,64}")
                ? clientToken
                : LanPairing.newToken();
        String peer = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();

        JSONObject payload = new JSONObject();
        payload.put("ok", true);
        payload.put("token", token);
        payload.put("watchName", watchName);
        send(socket, 200, payload.toString());

        if (listener != null) {
            listener.onPaired(deviceName, token, peer);
        }
    }

    /**
     * 读到空行为止的请求头（含结尾的 CRLFCRLF），返回原始字节。连一个字节都没有时返回 null。
     */
    private byte[] readHead(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        int matched = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            buffer.write(b);
            switch (matched) {
                case 0:
                    matched = b == '\r' ? 1 : 0;
                    break;
                case 1:
                    matched = b == '\n' ? 2 : (b == '\r' ? 1 : 0);
                    break;
                case 2:
                    matched = b == '\r' ? 3 : 0;
                    break;
                default:
                    matched = b == '\n' ? 4 : (b == '\r' ? 1 : 0);
                    break;
            }
            if (matched == 4) {
                break;
            }
        }
        return buffer.size() == 0 ? null : buffer.toByteArray();
    }

    private byte[] readBody(InputStream in, Map<String, String> headers) throws Exception {
        String transfer = headers.get("transfer-encoding");
        if (transfer != null && transfer.toLowerCase(Locale.US).contains("chunked")) {
            return readChunked(in);
        }

        int length = 0;
        String lengthHeader = headers.get("content-length");
        if (lengthHeader != null) {
            try {
                length = Integer.parseInt(lengthHeader.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (length <= 0) {
            return new byte[0];
        }
        if (length > MAX_BODY_BYTES) {
            length = MAX_BODY_BYTES;
        }

        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(body, offset, length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        if (offset == length) {
            return body;
        }
        byte[] partial = new byte[offset];
        System.arraycopy(body, 0, partial, 0, offset);
        return partial;
    }

    private byte[] readChunked(InputStream in) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(256);
        while (body.size() < MAX_BODY_BYTES) {
            String sizeLine = readAsciiLine(in);
            if (sizeLine == null) {
                break;
            }
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size <= 0) {
                readAsciiLine(in);
                break;
            }
            byte[] chunk = new byte[size];
            int offset = 0;
            while (offset < size) {
                int read = in.read(chunk, offset, size - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            body.write(chunk, 0, offset);
            readAsciiLine(in);
        }
        return body.toByteArray();
    }

    private String readAsciiLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream(16);
        int b = -1;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        if (b == -1 && line.size() == 0) {
            return null;
        }
        return new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private void send(Socket socket, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "ERROR") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void closeQuietly() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
    }
}
