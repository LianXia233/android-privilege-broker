#!/usr/bin/env bash
# 构建完整 APK：资源生成 -> Rust 测试 -> 交叉编译 -> jniLibs -> Gradle 打包签名
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
JNI_DIR="$ROOT/android/app/src/main/jniLibs"
APK_NAME="PrivilegeBroker.apk"

ABIS="arm64-v8a armeabi-v7a x86 x86_64"
TARGET_ARGS=()
for abi in $ABIS; do
    TARGET_ARGS+=("-t" "$abi")
done

echo "[1/5] 生成 AXML 测试样本与图标资源"
python3 "$ROOT/scripts/gen_axml_fixture.py"
python3 "$ROOT/scripts/gen_icons.py"

echo "[2/5] cargo test --workspace"
(cd "$ROOT" && cargo test --workspace)

echo "[3/5] 清理旧命名 native 库"
# Java 侧只加载 installer_core；清除所有非当前命名的 core 库，避免旧产物被打进 APK。
find "$JNI_DIR" -type f -name 'lib*_core.so' ! -name 'libinstaller_core.so' -delete 2>/dev/null || true

echo "[4/5] cargo-ndk 交叉编译 libinstaller_core.so ($ABIS)"
mkdir -p "$JNI_DIR"
(cd "$ROOT" && cargo ndk "${TARGET_ARGS[@]}" -o "$JNI_DIR" build --release -p installer-android-ffi)

echo "[5/5] Gradle assembleDebug"
(cd "$ROOT/android" && ./gradlew assembleDebug --no-daemon)

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
cp -f "$APK" "$ROOT/$APK_NAME"
echo "完成: $ROOT/$APK_NAME ($(stat -c%s "$ROOT/$APK_NAME") bytes)"
