package io.github.lianxia233.privbroker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自主特权代理客户端。
 *
 * 与独立进程之间只用自定义长度前缀帧通信：4 字节大端长度 + UTF-8 JSON。
 * 握手为挑战响应，令牌不反复出现在链路上。协议字段、错误码、证明算法
 * 与 Rust broker-protocol / broker-auth 完全一致，任一侧改动都必须同步。
 *
 * 承载优先使用 Android abstract Unix socket（无需文件系统权限），
 * 也支持 TCP loopback 配合无线调试端口转发，便于在 SELinux 限制较严的设备上降级。
 */
final class BrokerClient {
    private static final String TAG = "BrokerClient";
    private static final String PREFS = "privilege_broker_link";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_CLIENT = "client_id";
    static final String DEFAULT_CLIENT = "installer";
    static final int PROTOCOL_VERSION = 2;
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final byte[] PROOF_DOMAIN = "privilege-broker/v2/challenge-proof".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SOCKET_DOMAIN = "privilege-broker/socket/v2".getBytes(StandardCharsets.UTF_8);
    private static final long DEADLINE_MS = 30_000L;

    /**
     * 进程级递增 nonce。服务端的 Authenticator 按 client_id 全局去重（30s 窗口），
     * 每次 BrokerBridge 调用都会新建连接并重新握手，因此 nonce 绝不能按连接重置为 1，
     * 否则第二次握手就会被判定为重放。以墙钟毫秒为种子，保证跨连接、跨重启不重复。
     */
    private static final AtomicLong NONCE_SEQ =
            new AtomicLong(System.currentTimeMillis());

    private static long nextNonce() {
        return NONCE_SEQ.getAndIncrement();
    }

    private final InputStream in;
    private final OutputStream out;
    private final Socket tcpSocket;
    private final LocalSocket localSocket;
    private final String clientId;
    private long sessionId;
    private long nonce;
    private int serverUid = -1;
    private int protocolVersion = PROTOCOL_VERSION;

    private BrokerClient(InputStream in, OutputStream out, Socket tcp, LocalSocket local, String clientId) {
        this.in = in;
        this.out = out;
        this.tcpSocket = tcp;
        this.localSocket = local;
        this.clientId = clientId;
    }

    // ------------------------------------------------------------ 配置

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 进程级配对令牌，首次使用时生成一次并持久化。 */
    static String token(Context c) {
        SharedPreferences p = prefs(c);
        String t = p.getString(KEY_TOKEN, null);
        if (t == null || t.length() < 32) {
            byte[] rnd = new byte[24];
            new SecureRandom().nextBytes(rnd);
            t = hex(rnd);
            p.edit().putString(KEY_TOKEN, t).apply();
        }
        return t;
    }

    /** 由用户改写为配对令牌时调用，用于与独立进程对齐。 */
    static void setToken(Context c, String t) {
        prefs(c).edit().putString(KEY_TOKEN, t == null ? "" : t.trim()).apply();
    }

    static String endpoint(Context c) {
        return prefs(c).getString(KEY_ENDPOINT, "abstract");
    }

    static void setEndpoint(Context c, String ep) {
        prefs(c).edit().putString(KEY_ENDPOINT, ep == null ? "abstract" : ep.trim()).apply();
    }

    static String clientId(Context c) {
        String v = prefs(c).getString(KEY_CLIENT, DEFAULT_CLIENT);
        return v == null || v.isEmpty() ? DEFAULT_CLIENT : v;
    }

    /** abstract socket 名，算法与 Rust broker-android::socket_name 一致。 */
    static String socketName(Context c) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(SOCKET_DOMAIN);
            md.update(token(c).getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder s = new StringBuilder("prvbrk.");
            for (int i = 0; i < 8; i++) {
                s.append(String.format("%02x", d[i]));
            }
            return s.toString();
        } catch (Throwable t) {
            throw new IllegalStateException("SHA-256 不可用", t);
        }
    }

    // ------------------------------------------------------------ 连接与握手

    static BrokerClient connect(Context c) throws Exception {
        String ep = endpoint(c);
        if ("abstract".equals(ep) || ep.startsWith("abstract:")) {
            String name = ep.startsWith("abstract:")
                    ? ep.substring("abstract:".length()) : socketName(c);
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                s.setSoTimeout(30_000);
                return handshake(new BrokerClient(s.getInputStream(), s.getOutputStream(), null, s, clientId(c)), c);
            } catch (Throwable t) {
                try { s.close(); } catch (Throwable ignored) { }
                throw new IllegalStateException("无法连接特权代理 (abstract:" + name + "): " + t, t);
            }
        }
        if (ep.startsWith("tcp:")) {
            String hp = ep.substring(4);
            int colon = hp.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("TCP 端点格式应为 tcp:host:port");
            }
            String host = hp.substring(0, colon);
            int port = Integer.parseInt(hp.substring(colon + 1));
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 5_000);
                s.setSoTimeout(120_000);
                s.setTcpNoDelay(true);
                return handshake(new BrokerClient(s.getInputStream(), s.getOutputStream(), s, null, clientId(c)), c);
            } catch (Throwable t) {
                try { s.close(); } catch (Throwable ignored) { }
                throw new IllegalStateException("无法连接特权代理 (" + ep + "): " + t, t);
            }
        }
        throw new IllegalArgumentException("未知端点类型: " + ep);
    }

    private static BrokerClient handshake(BrokerClient c, Context ctx) throws Exception {
        long now = System.currentTimeMillis();
        c.nonce = nextNonce();
        c.writeFrame(new JSONObject()
                .put("version", PROTOCOL_VERSION)
                .put("requestId", c.nonce)
                .put("sessionId", 0)
                .put("clientId", c.clientId)
                .put("nonce", c.nonce)
                .put("issuedAtMs", now)
                .put("deadlineMs", now + DEADLINE_MS)
                .put("body", new JSONObject()
                        .put("type", "hello")
                        .put("token", token(ctx))
                        .put("advertisedVersion", PROTOCOL_VERSION)));

        JSONObject challenge = c.readFrame();
        JSONObject cb = challenge.optJSONObject("body");
        if (cb == null || !"challenge".equals(cb.optString("type"))) {
            throw new IllegalStateException("握手首帧不是 challenge: " + challenge);
        }
        long serverNonce = cb.getLong("serverNonce");

        long t2 = System.currentTimeMillis();
        String proof = computeProof(token(ctx), serverNonce, c.clientId, t2);
        c.nonce = nextNonce();
        c.writeFrame(new JSONObject()
                .put("version", PROTOCOL_VERSION)
                .put("requestId", c.nonce)
                .put("sessionId", 0)
                .put("clientId", c.clientId)
                .put("nonce", c.nonce)
                .put("issuedAtMs", t2)
                .put("deadlineMs", t2 + DEADLINE_MS)
                .put("body", new JSONObject()
                        .put("type", "challengeProof")
                        .put("serverNonce", serverNonce)
                        .put("proof", proof)));

        JSONObject ack = c.readFrame();
        JSONObject ab = ack.optJSONObject("body");
        if (ab == null || !"authAck".equals(ab.optString("type")) || !ab.optBoolean("granted")) {
            throw new IllegalStateException("握手失败: " + ack);
        }
        c.sessionId = ack.optLong("sessionId", ab.optLong("sessionId", 0));
        c.serverUid = ab.optInt("uid", -1);
        c.protocolVersion = ab.optInt("serverVersion", PROTOCOL_VERSION);
        return c;
    }

    /** 与 Rust broker-auth::compute_proof 逐字节一致。 */
    static String computeProof(String token, long serverNonce, String clientId, long issuedAtMs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(PROOF_DOMAIN);
            byte[] tb = token.getBytes(StandardCharsets.UTF_8);
            md.update(u64(tb.length));
            md.update(tb);
            md.update(u64(serverNonce));
            byte[] cb = clientId.getBytes(StandardCharsets.UTF_8);
            md.update(u64(cb.length));
            md.update(cb);
            md.update(u64(issuedAtMs));
            return hex(md.digest());
        } catch (Throwable t) {
            throw new IllegalStateException("SHA-256 不可用", t);
        }
    }

    private static byte[] u64(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    private static String hex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xFF;
            s.append(digits[v >>> 4]).append(digits[v & 0x0F]);
        }
        return s.toString();
    }

    // ------------------------------------------------------------ 帧读写

    private void writeFrame(JSONObject env) throws Exception {
        byte[] payload = env.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IllegalStateException("帧超过上限");
        }
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(payload.length);
        d.write(payload);
        d.flush();
    }

    private JSONObject readFrame() throws Exception {
        DataInputStream d = new DataInputStream(in);
        int n = d.readInt();
        if (n <= 0 || n > MAX_FRAME_BYTES) {
            throw new IllegalStateException("非法帧长度: " + n);
        }
        byte[] payload = new byte[n];
        d.readFully(payload);
        return new JSONObject(new String(payload, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ 请求

    private JSONObject envelope(JSONObject body) {
        long now = System.currentTimeMillis();
        nonce = nextNonce();
        try {
            return new JSONObject()
                    .put("version", PROTOCOL_VERSION)
                    .put("requestId", nonce)
                    .put("sessionId", sessionId)
                    .put("clientId", clientId)
                    .put("nonce", nonce)
                    .put("issuedAtMs", now)
                    .put("deadlineMs", now + DEADLINE_MS)
                    .put("body", body);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** 读到最终 response 为止，中间的流式帧合并进 stdout / stderr。 */
    public JSONObject exec(String[] argv, JSONObject stdin, long timeoutMs) throws Exception {
        JSONArray arr = new JSONArray();
        for (String a : argv) {
            arr.put(a);
        }
        JSONObject body = new JSONObject()
                .put("type", "exec")
                .put("argv", arr)
                .put("stdin", stdin == null ? new JSONObject().put("type", "none") : stdin)
                .put("env", new JSONArray())
                .put("timeoutMs", timeoutMs > 0 ? timeoutMs : 120_000L)
                .put("cancelGroup", 0);
        JSONObject req = envelope(body);
        long requestId = req.getLong("requestId");
        writeFrame(req);

        ByteArrayOutputStream so = new ByteArrayOutputStream();
        ByteArrayOutputStream se = new ByteArrayOutputStream();
        int code = -1;
        String error = "";
        while (true) {
            JSONObject rep = readFrame();
            if (rep.optLong("requestId", -1) != requestId) {
                continue; // 忽略其它请求的残留帧
            }
            JSONObject b = rep.optJSONObject("body");
            if (b == null) {
                continue;
            }
            String type = b.optString("type");
            switch (type) {
                case "execStarted":
                case "execFinished":
                    if ("execFinished".equals(type)) {
                        code = b.optInt("code", code);
                    }
                    break;
                case "execChunk": {
                    byte[] bytes = unhex(b.optString("hex", ""));
                    if ("STDERR".equals(b.optString("stream"))) {
                        se.write(bytes);
                    } else {
                        so.write(bytes);
                    }
                    break;
                }
                case "response": {
                    String ec = b.optString("code", "OK");
                    if (!"OK".equals(ec)) {
                        error = ec + ": " + b.optString("message", "");
                    }
                    JSONObject payload = b.optJSONObject("payload");
                    if (payload != null) {
                        code = payload.optInt("code", code);
                        // 服务端已给出完整文本时优先采用，避免分片编码歧义
                        String sOut = payload.optString("stdout", null);
                        String sErr = payload.optString("stderr", null);
                        if (sOut != null) { so = new ByteArrayOutputStream(); so.write(sOut.getBytes(StandardCharsets.UTF_8)); }
                        if (sErr != null) { se = new ByteArrayOutputStream(); se.write(sErr.getBytes(StandardCharsets.UTF_8)); }
                    }
                    JSONObject r = new JSONObject()
                            .put("code", code)
                            .put("stdout", so.toString("UTF-8"))
                            .put("stderr", se.toString("UTF-8") + (error.isEmpty() ? "" : "\n" + error));
                    return r;
                }
                default:
                    break;
            }
        }
    }

    private static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(2 * i), 16) << 4)
                    | Character.digit(s.charAt(2 * i + 1), 16));
        }
        return out;
    }

    public int serverUid() {
        return serverUid;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public void close() {
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Throwable ignored) { }
        try { if (localSocket != null) localSocket.close(); } catch (Throwable ignored) { }
        try { in.close(); } catch (Throwable ignored) { }
        try { out.close(); } catch (Throwable ignored) { }
        Log.i(TAG, "连接已关闭");
    }
}
