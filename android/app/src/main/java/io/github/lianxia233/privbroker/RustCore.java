package io.github.lianxia233.privbroker;

/** Rust 核心的唯一入口。所有业务逻辑都在 installer-core crate 中。 */
public final class RustCore {
    static {
        System.loadLibrary("installer_core");
    }

    private RustCore() {
    }

    public static native String dispatch(String method, String argsJson);
}
