package io.github.lianxia233.privbroker;

import org.json.JSONObject;

/**
 * 由无线调试的 shell 经 app_process 拉起，进程身份为 shell(2000)。
 *
 * 该类不依赖任何 Android 组件：CLASSPATH 指向 APK，framework 的 boot classloader
 * 提供 org.json；随后按 nativeLibraryDir 绝对路径加载 native 库并阻塞运行服务。
 */
public final class ServiceStarter {

    private ServiceStarter() {
    }

    public static void main(String[] args) {
        String token = "";
        String client = "installer";
        String endpoint = "abstract";
        String lib = "";
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
                }
            }
        }
        try {
            JSONObject j = new JSONObject();
            j.put("token", token);
            j.put("client", client);
            j.put("endpoint", endpoint);
            WirelessCore.ensureLoaded(lib);
            WirelessCore.startBroker(j.toString());
        } catch (Throwable t) {
            System.err.println("特权代理启动失败: " + t);
        }
    }
}
