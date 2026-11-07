# JNI 入口必须保留
-keep class io.github.lianxia233.privbroker.RustCore { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
