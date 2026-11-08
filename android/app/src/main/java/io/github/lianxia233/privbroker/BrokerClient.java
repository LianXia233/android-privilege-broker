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
    private static final byte[] TCP_DOMAIN = "privilege-broker/tcp/v2".getBytes(StandardCharsets.UTF_8);
    /** 按配对令牌派生的 TCP loopback 端口落在这一段，避开常用端口。 */
    private static final int TCP_PORT_BASE = 20000;
    private static final int TCP_PORT_SPAN = 40000;
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

    /** 令牌首次生成必须是单写入者：并发首用若各自生成，会得到两个不同的令牌。 */
    private static final Object TOKEN_LOCK = new Object();

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
        if (t != null && t.length() >= 32) {
            return t;
        }
        // 无线调试拉起、状态查询与安装步骤可能并发首次取令牌；不加锁时
        // 两个线程会各自生成并互相覆盖，代理进程拿到的令牌与应用后续读取的
        // 不再是同一个，所有握手都会稳定被判 UNAUTHENTICATED。
        synchronized (TOKEN_LOCK) {
            t = p.getString(KEY_TOKEN, null);
            if (t == null || t.length() < 32) {
                byte[] rnd = new byte[24];
                new SecureRandom().nextBytes(rnd);
                t = hex(rnd);
                p.edit().putString(KEY_TOKEN, t).commit();
            }
            return t;
        }
    }

    /** 由用户改写为配对令牌时调用，用于与独立进程对齐。 */
    static void setToken(Context c, String t) {
        prefs(c).edit().putString(KEY_TOKEN, t == null ? "" : t.trim()).apply();
    }

    /**
     * 默认端点使用 TCP loopback。SELinux enforcing 下 app 域无法 connect shell
     * 域创建的 abstract Unix socket，只有本机 TCP 回环能被两侧同时访问。旧版本
     * 把默认值写死为 "abstract"，这里在读取时自动迁移到按令牌派生的端口，
     * 保证覆盖安装升级后第一次激活就走可连通的通道。
     */
    static String endpoint(Context c) {
        String ep = prefs(c).getString(KEY_ENDPOINT, null);
        if (ep == null || ep.isEmpty() || "abstract".equals(ep)) {
            return "tcp:127.0.0.1:" + tcpPort(c);
        }
        return ep;
    }

    static void setEndpoint(Context c, String ep) {
        // 传空等价于恢复默认的 TCP loopback 端点，不再写死 "abstract"。
        prefs(c).edit().putString(KEY_ENDPOINT, ep == null ? "" : ep.trim()).apply();
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

    /** 由配对令牌派生的稳定 TCP loopback 端口，app 与代理进程两侧一致。 */
    private static int tcpPort(Context c) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(TCP_DOMAIN);
            md.update(token(c).getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            int v = ((d[0] & 0xff) << 8) | (d[1] & 0xff);
            return TCP_PORT_BASE + Math.floorMod(v, TCP_PORT_SPAN);
        } catch (Throwable ignored) {
            return TCP_PORT_BASE;
        }
    }

    /**
     * 全局唯一的「特权代理是否在运行」判据：能连上监听端点即视为在跑。
     * 状态徽章、激活结果、通知栏自动激活三处共用本方法，杜绝各处判据不一致
     * 造成的「结果条报成功、状态区却显示未运行」。只做连接探测、不做握手，
     * 600ms 超时，可在任意线程调用。
     */
    static boolean probe(Context c) {
        String ep = endpoint(c);
        if (ep.startsWith("tcp:")) {
            String hp = ep.substring(4);
            int colon = hp.lastIndexOf(':');
            if (colon <= 0) {
                return false;
            }
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(hp.substring(0, colon),
                        Integer.parseInt(hp.substring(colon + 1))), 600);
                return true;
            } catch (Throwable ignored) {
                return false;
            } finally {
                try { s.close(); } catch (Throwable ignored) { }
            }
        }
        String name = ep.startsWith("abstract:")
                ? ep.substring("abstract:".length()) : socketName(c);
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 端点可连且握手成功才算「真的可用」。
     *
     * <p>只探测端口会漏掉「进程在监听但拒绝当前令牌」的陈旧实例：此时
     * 恢复流程会误判已恢复，重试仍然是同一个拒绝码。
     * 判定用完整握手，代价是每次多一次短连接，只在恢复/诊断路径使用。</p>
     */
    static boolean handshake(Context c) {
        try {
            BrokerClient client = connect(c);
            try {
                return client.serverUid() > 0;
            } finally {
                client.close();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------ 连接与握手

    /**
     * 建立连接并完成握手。握手阶段的鉴权类拒绝（挑战失效、重放窗口、会话
     * 过期）都来自一次性凭据失效，换一组新 nonce 与新挑战即可恢复；这里
     * 只重试一次，不做无限重连，也绝不降级为无鉴权通道。
     */
    static BrokerClient connect(Context c) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return open(c);
            } catch (Exception e) {
                last = e;
                if (attempt == 0 && retryableHandshakeRejection(e)) {
                    Log.w(TAG, "握手被拒，换新 nonce/挑战重试一次", e);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    /**
     * 只对「换一组新 nonce 与新挑战就可能成功」的拒绝重试一次。
     *
     * <p>CHALLENGE_UNKNOWN 表示本次挑战槽位已失效（过期、被淘汰或同位竞争），
     * 属于一次性状态，重取挑战即可恢复；PROOF_MISMATCH 表示令牌或 client_id
     * 与服务端不一致，换 nonce 也无济于事，直接上抛，避免把真实原因拖成第二次
     * 相同的失败。</p>
     */
    private static boolean retryableHandshakeRejection(Throwable t) {
        String msg = String.valueOf(t.getMessage());
        if (!msg.contains("握手失败")) {
            return false;
        }
        return msg.contains("UNAUTHENTICATED")
                || msg.contains("CHALLENGE_UNKNOWN")
                || msg.contains("SESSION_EXPIRED")
                || msg.contains("REPLAY_DETECTED");
    }

    private static BrokerClient open(Context c) throws Exception {
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
        // 一次握手只取一次令牌快照：Hello 与 Proof 必须基于同一个值。
        // 两次分别读取时，中途一旦发生写入（首次生成、重新配对），就会出现
        // Hello 的令牌通过、Proof 的证明用的是另一个令牌，最终被判证明不成立。
        String token = token(ctx);
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
                        .put("token", token)
                        .put("advertisedVersion", PROTOCOL_VERSION)));

        JSONObject challenge = c.readFrame();
        JSONObject cb = challenge.optJSONObject("body");
        if (cb == null || !"challenge".equals(cb.optString("type"))) {
            throw new IllegalStateException("握手首帧不是 challenge: " + challenge);
        }
        long serverNonce = exactServerNonce(cb);

        long t2 = System.currentTimeMillis();
        String proof = computeProof(token, serverNonce, c.clientId, t2);
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

    /**
     * 读取服务端挑战随机数，拒绝一切会丢精度的表示。
     *
     * <p>服务端下发的是随机整数，而帧内容由 org.json 解析：超出 long 范围的
     * 数字字面量会退化成 double，取整后得到的值不再等于服务端登记的值。
     * 用这个值计算证明并按值回传，服务端按值精确匹配挑战槽位必然落空，最终
     * 表现为「Hello 通过、Proof 被拒」且日志里只有一个笼统的拒绝码。这里把
     * double/float 表示显式识别出来并给出可读原因，避免同类问题再次以静默
     * 形式出现（新版本代理已把随机数限制在 53 位内）。</p>
     */
    private static long exactServerNonce(JSONObject body) {
        Object raw = body.opt("serverNonce");
        if (raw instanceof Number) {
            Number n = (Number) raw;
            if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
                return n.longValue();
            }
            throw new IllegalStateException("握手失败: 挑战随机数超出 JSON 精确整数范围（serverNonce="
                    + n + "）：特权代理随机数未限制在 53 位内，请重新激活以更新代理");
        }
        throw new IllegalStateException("握手失败: 挑战帧缺少 serverNonce: " + body);
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
