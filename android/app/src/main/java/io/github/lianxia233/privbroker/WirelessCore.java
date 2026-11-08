package io.github.lianxia233.privbroker;

import java.util.ArrayList;
import java.util.List;

/**
 * 无线调试链路的 native 入口。
 *
 * 应用进程使用 {@link #run(String)} 完成配对、连接与启动；由 app_process 拉起的
 * shell 进程使用 {@link #startBroker(String)} 常驻运行特权代理。两者共用同一个
 * native 库，但加载方式不同，必须分别保证可用。
 *
 * 应用进程有系统的 ClassLoader 上下文，{@code System.loadLibrary} 能从 APK 里
 * 找到 so（无论是否解压）。而 app_process(shell 2000) 进程没有应用上下文：
 * 若 APK 声明 {@code extractNativeLibs=false}，so 未压缩留在 APK 内、
 * nativeLibraryDir 下没有真实文件，绝对路径 {@code System.load} 必然失败，
 * 代理进程直接退出 —— 表现为「配对成功但服务始终未运行」。
 *
 * 因此 shell 进程按真实文件位置多路径尝试，并把每一次失败打到 stderr
 * （启动日志会回显到界面结果条），最后一律给出可诊断的异常。
 */
public final class WirelessCore {

    private static final String LIB = "libinstaller_core.so";
    /** 与 build.gradle.kts 的 abiFilters 一致；非本机 ABI 会 dlopen 失败并被跳过。 */
    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};

    private static boolean loaded;

    private WirelessCore() {
    }

    /** 应用进程入口：交给系统 ClassLoader 加载。 */
    public static synchronized void ensureLoaded(String nativeLibDir) {
        ensureLoaded(nativeLibDir, null);
    }

    /**
     * shell 进程入口：{@code apkPath} 为启动命令里 CLASSPATH 指向的 APK。
     * 依次尝试解压后的绝对路径、APK 内未压缩对齐的 so、以及 loadLibrary 兜底，
     * 覆盖 extractNativeLibs 两种取值与不同 ROM 的安装行为。
     */
    public static synchronized void ensureLoaded(String nativeLibDir, String apkPath) {
        if (loaded) {
            return;
        }
        List<String> paths = new ArrayList<>();
        if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
            paths.add(nativeLibDir + "/" + LIB);
        }
        if (apkPath != null && !apkPath.isEmpty()) {
            for (String abi : ABIS) {
                paths.add(apkPath + "!/lib/" + abi + "/" + LIB);
            }
        }

        Throwable last = null;
        for (String path : paths) {
            try {
                System.load(path);
                loaded = true;
                return;
            } catch (Throwable t) {
                last = t;
                System.err.println("加载 native 库失败: " + path + " -> " + t);
            }
        }
        try {
            System.loadLibrary("installer_core");
            loaded = true;
            return;
        } catch (Throwable t) {
            last = t;
            System.err.println("loadLibrary 兜底失败: " + t);
        }
        throw new UnsatisfiedLinkError("无法加载 " + LIB + "（已尝试 " + paths + "）: " + last);
    }

    /** 返回 JSON：ok / stage / code / message / output。 */
    public static native String run(String argsJson);

    /** 阻塞运行特权代理，直到服务停止；仅在 shell(2000) 进程中调用。 */
    public static native void startBroker(String argsJson);
}
