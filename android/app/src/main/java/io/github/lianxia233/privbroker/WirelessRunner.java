package io.github.lianxia233.privbroker;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 无线调试链路的两阶段调用封装。
 *
 * 参考成熟实现，配对（注册 ADB 公钥）与启动（连接无线调试并执行 shell）是
 * 两个互不相干的阶段：配对服务只在「使用配对码配对设备」弹窗打开期间存在，
 * 配对成功后系统会重分配连接服务端口。因此这里把它们拆成两次 native 调用：
 * {@link #pair} 只做配对并返回 stage=paired；调用方随后重新发现连接端口，
 * 再用 {@link #start} 连接并启动。{@link #full} 保留给通知栏兼容路径。
 *
 * 连接地址不再固定为 loopback。部分 ROM 的 adbd 把配对/连接端口绑在 WLAN
 * 接口 IP（设置页「IP 地址和端口」显示的就是它），只用 127.0.0.1 会连不上。
 * 这里把 mDNS 解析地址、本机网卡 IPv4 与 loopback 组成候选列表传给 native，
 * 由连接层逐个尝试，任一连通即用。
 *
 * 所有异常都转成结构化 JSON 返回，绝不向上抛（避免 JNI 边界崩溃）。
 */
final class WirelessRunner {

    static final String STAGE_PAIRED = "paired";
    static final String STAGE_STARTED = "started";

    private WirelessRunner() {
    }

    static String pair(Context c, int pairingPort, String code, String mdnsHost) {
        try {
            JSONObject args = base(c, mdnsHost);
            args.put("stage", "pair");
            args.put("pairingPort", pairingPort);
            args.put("pairingCode", code);
            args.put("connectPort", 0);
            return call(args);
        } catch (Throwable t) {
            return error("PAIR", t);
        }
    }

    static String start(Context c, int connectPort, String mdnsHost) {
        try {
            JSONObject args = base(c, mdnsHost);
            args.put("stage", "start");
            args.put("connectPort", connectPort);
            args.put("pairingPort", 0);
            args.put("pairingCode", "");
            return call(args);
        } catch (Throwable t) {
            return error("START", t);
        }
    }

    static String full(Context c, int pairingPort, String code, int connectPort, String mdnsHost) {
        try {
            JSONObject args = base(c, mdnsHost);
            args.put("pairingPort", pairingPort);
            args.put("pairingCode", code);
            args.put("connectPort", connectPort);
            return call(args);
        } catch (Throwable t) {
            return error("FULL", t);
        }
    }

    private static JSONObject base(Context c, String mdnsHost) throws Exception {
        JSONObject args = new JSONObject();
        String[] hosts = MdnsFilter.candidates(mdnsHost);
        JSONArray candidates = new JSONArray();
        for (String h : hosts) {
            candidates.put(h);
        }
        args.put("hosts", candidates);
        // 保留 host 字段兼容旧 native 接口（取优先级最高的候选）
        args.put("host", hosts.length > 0 ? hosts[0] : "127.0.0.1");
        args.put("keyPath", new File(c.getFilesDir(), "adb_key.pk8").getAbsolutePath());
        args.put("apkPath", c.getApplicationInfo().sourceDir);
        args.put("nativeLibDir", c.getApplicationInfo().nativeLibraryDir);
        args.put("token", BrokerClient.token(c));
        args.put("client", BrokerClient.clientId(c));
        args.put("endpoint", BrokerClient.endpoint(c));
        return args;
    }

    private static String call(JSONObject args) {
        try {
            WirelessCore.ensureLoaded(null);
            return WirelessCore.run(args.toString());
        } catch (Throwable t) {
            return error("BRIDGE", t);
        }
    }

    private static String error(String stage, Throwable t) {
        return "{\"ok\":false,\"stage\":\"" + stage
                + "\",\"code\":\"BRIDGE\",\"message\":"
                + JSONObject.quote(String.valueOf(t)) + "}";
    }
}
