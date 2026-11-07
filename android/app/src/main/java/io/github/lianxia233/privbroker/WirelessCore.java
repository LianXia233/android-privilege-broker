package io.github.lianxia233.privbroker;

/**
 * 无线调试链路的 native 入口。
 *
 * 应用进程使用 {@link #run(String)} 完成配对、连接与启动；由 app_process 拉起的
 * shell 进程使用 {@link #startBroker(String)} 常驻运行特权代理。两者共用同一个
 * native 库：应用进程走 loadLibrary，shell 进程通过 nativeLibraryDir 绝对路径加载。
 */
public final class WirelessCore {

    private static boolean loaded;

    private WirelessCore() {
    }

    public static synchronized void ensureLoaded(String nativeLibDir) {
        if (loaded) {
            return;
        }
        if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
            System.load(nativeLibDir + "/libinstaller_core.so");
        } else {
            System.loadLibrary("installer_core");
        }
        loaded = true;
    }

    /** 返回 JSON：ok / stage / code / message / output。 */
    public static native String run(String argsJson);

    /** 阻塞运行特权代理，直到服务停止；仅在 shell(2000) 进程中调用。 */
    public static native void startBroker(String argsJson);
}
