package io.github.lianxia233.privbroker;

import org.json.JSONObject;

/**
 * 由无线调试的 shell 经 app_process 拉起，进程身份为 shell(2000)。
 *
 * 该类不依赖任何 Android 组件：CLASSPATH 指向 APK，framework 的 boot classloader
 * 提供 org.json；随后按 nativeLibraryDir 绝对路径加载 native 库并阻塞运行服务。
 *
 * 常驻的关键：启动命令已用 setsid 让本进程从启动起就处于独立会话，并重定向
 * stdio 后台化。这里再调用 {@code Os.setsid()} 作为兜底 —— 若设备缺少 setsid
 * 命令、或本进程还不是会话首进程，仍可脱离 adbd 的会话/进程组。否则 shell
 * 通道关闭时 adbd 会向整个进程组发信号，把刚拉起的代理一起杀掉。
 */
public final class ServiceStarter {

    private ServiceStarter() {
    }

    public static void main(String[] args) {
        // 脱离 adbd 会话，避免通道收尾时被连带终止。已是会话首进程时 setsid 会
        // 失败，但命令层已重定向 stdio，此刻继续运行即可，不视为致命错误。
        try {
            android.system.Os.setsid();
        } catch (Throwable ignored) {
        }
        System.err.println("特权代理进程已拉起，正在加载运行时…");

        String token = "";
        String client = "installer";
        String endpoint = "abstract";
        String lib = "";
        String apk = "";
        if (args != null) {
            for (String a : args) {
                if (a == null) {
                    continue;
                }
                if (a.startsWith("--token=")) {
                    token = a.substring("--token=".length());
                } else if (a.startsWith("--client=")) {
                    client = a.substring("--client=".length());
                } else if (a.startsWith("--endpoint=")) {
                    endpoint = a.substring("--endpoint=".length());
                } else if (a.startsWith("--lib=")) {
                    lib = a.substring("--lib=".length());
                } else if (a.startsWith("--apk=")) {
                    apk = a.substring("--apk=".length());
                }
            }
        }
        // CLASSPATH 环境变量由启动命令设置，作为 --apk 缺失时的兜底来源。
        if (apk.isEmpty()) {
            String cp = System.getenv("CLASSPATH");
            if (cp != null && !cp.isEmpty()) {
                apk = cp;
            }
        }
        try {
            JSONObject j = new JSONObject();
            j.put("token", token);
            j.put("client", client);
            j.put("endpoint", endpoint);
            // native 库加载失败是「服务起不来」最常见的原因，这里必须最先做，
            // 且失败信息会随启动日志回显到界面结果条，便于定位。
            try {
                WirelessCore.ensureLoaded(lib, apk);
            } catch (Throwable t) {
                System.err.println("无法加载 native 库，特权代理无法启动: " + t);
                throw t;
            }
            WirelessCore.startBroker(j.toString());
        } catch (Throwable t) {
            System.err.println("特权代理启动失败: " + t);
        }
    }
}
