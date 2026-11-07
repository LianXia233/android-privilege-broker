# JNI 入口、native 桥与 app_process 启动类必须保留完整类名
-keep class io.github.lianxia233.privbroker.RustCore { *; }
-keep class io.github.lianxia233.privbroker.WirelessCore { *; }
-keep class io.github.lianxia233.privbroker.ServiceStarter { *; }
-keep class io.github.lianxia233.privbroker.WirelessPairingService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
