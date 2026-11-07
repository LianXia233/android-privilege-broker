plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.lianxia233.privbroker"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lianxia233.privbroker"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "2.1.2"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    /*
     * 固定签名密钥：文件随仓库一起分发，沙箱本地构建与 GitHub Actions 云构建
     * checkout 到的是同一个 keystore，签名证书因此逐字节一致，可互相覆盖安装。
     *
     * 为什么不沿用 ~/.android/debug.keystore：Android Gradle 插件会在每台机器
     * 首次构建时各自生成一个随机 debug 密钥，证书 SHA-256 必然不同，云编译产物
     * 与本地产物无法互相覆盖。显式指向仓库内文件是唯一能保证两端一致的方案。
     *
     * 注意：这是公开的自签名密钥，仅用于保证构建可复现与版本间签名一致，
     * 不提供任何身份信任；请勿把它当作私有发布密钥使用。
     */
    signingConfigs {
        create("shared") {
            storeFile = file("signing/release.keystore")
            // 以下三项是 release.keystore 内部的条目名与口令，必须与该文件逐字一致。
            // 改动会令证书指纹变化、已发布版本无法覆盖安装，与产品命名无关，勿随文案替换。
            storePassword = "privilege-broker"
            keyAlias = "privilege-broker"
            keyPassword = "privilege-broker"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            // scripts/build.sh 走 assembleDebug，必须同样显式指定固定密钥，
            // 否则 Gradle 会回退到随机生成的 debug.keystore。
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Rust 产物由 scripts/build.sh 通过 cargo-ndk 生成后放入 src/main/jniLibs。
    // 这里不注册 NDK 构建任务，保证 Gradle 与 Rust 工具链解耦。
    lint {
        abortOnError = false
    }
}

// 特权执行完全依赖仓库内自主实现的 broker（Rust + 本地 IPC），
// 不引入任何第三方特权框架 SDK / AIDL / Provider。
dependencies {
}
