package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 关于页：应用简介、开源仓库、开源许可清单（点击跳转上游仓库）。
 * 纯静态，不含任何静默安装能力。
 */
final class AboutPage {
    private static final String REPO_URL =
            "https://github.com/LianXia233/android-privilege-broker";

    /** 与仓库真实的 Cargo.toml / build.gradle.kts 依赖保持一致。 */
    private static final String[][] DEPS = {
            {"serde · 1.x", "MIT / Apache-2.0",
                    "Rust 核心的配置读写与结构化数据序列化",
                    "https://github.com/serde-rs/serde"},
            {"serde_json · 1.x", "MIT / Apache-2.0",
                    "Java 与 Rust 之间的 JSON 参数 / 返回值桥接",
                    "https://github.com/serde-rs/json"},
            {"thiserror · 2.x", "MIT / Apache-2.0",
                    "核心错误类型与可读的失败原因",
                    "https://github.com/dtolnay/thiserror"},
            {"jni · 0.21.x", "MIT / Apache-2.0",
                    "把 Rust 核心编译为 cdylib 并暴露 JNI 接口给 Java 层",
                    "https://github.com/jni-rs/jni-rs"},
            {"Tokio · 1.x", "MIT",
                    "特权代理的异步运行时：多连接调度、异步进程执行与超时取消",
                    "https://github.com/tokio-rs/tokio"},
            {"libc · 0.2.x", "MIT / Apache-2.0",
                    "uid 实测、进程信号与 abstract Unix socket 的底层调用",
                    "https://github.com/rust-lang/libc"},
            {"sha2 · 0.10.x", "MIT / Apache-2.0",
                    "挑战响应证明与端点名派生的 SHA-256 摘要",
                    "https://github.com/RustCrypto/hashes"},
            {"hex · 0.4.x", "MIT / Apache-2.0",
                    "挑战证明与二进制 stdin 的十六进制编解码",
                    "https://github.com/KokaKiwi/rust-hex"},
            {"rustls · 0.23.x", "Apache-2.0 / ISC / MIT",
                    "TLS 1.3 握手与密钥导出，用于无线调试配对及 ADB TLS 通道",
                    "https://github.com/rustls/rustls"},
            {"rcgen · 0.14.x", "MIT / Apache-2.0",
                    "生成与配对公钥同源的自签客户端证书",
                    "https://github.com/rustls/rcgen"},
            {"rsa · 0.9.x", "MIT / Apache-2.0",
                    "RSA 2048 密钥、ADB 公钥结构编码与认证签名",
                    "https://github.com/RustCrypto/RSA"},
            {"aes-gcm · 0.11.x", "MIT / Apache-2.0",
                    "配对阶段 PeerInfo 的 AES-128-GCM 序列加密",
                    "https://github.com/RustCrypto/AEADs"},
            {"curve25519-dalek · 5.x", "BSD-3-Clause",
                    "SPAKE2 密钥交换的 Edwards25519 点运算",
                    "https://github.com/dalek-cryptography/curve25519-dalek"},
            {"base64 · 0.22.x", "MIT / Apache-2.0",
                    "ADB 公钥结构的编码",
                    "https://github.com/marshallpierce/rust-base64"},
            {"Rust 标准库", "MIT / Apache-2.0",
                    "Rust 核心运行时与标准集合类型",
                    "https://www.rust-lang.org/"},
            {"FlClash · prism_ui（设计参考）", "GPL-3.0",
                    "界面结构、配色生成规则与动画时长的参考实现；本项目以 Android 原生 View / 动画重写，不链接其 Dart 代码",
                    "https://github.com/chen08209/FlClash"},
            {"material-color-utilities", "Apache-2.0",
                    "M3 动态配色（HCT）算法；色板由 scripts/gen_palette.py 离线生成，色值随源码分发",
                    "https://github.com/material-foundation/material-color-utilities"},
            {"materialyoucolor（Python 端口）", "MIT",
                    "在开发期调用上述 HCT 算法生成色板，仅构建期使用，不随 APK 分发",
                    "https://pypi.org/project/materialyoucolor/"},
            {"Android SDK / Android Gradle Plugin", "Apache-2.0",
                    "应用构建、资源编译与 APK 签名（仅构建期，不随 APK 分发）",
                    "https://developer.android.com/"},
    };

    private final Activity a;
    private final View root;

    AboutPage(Activity a, View root) {
        this.a = a;
        this.root = root;
        bind();
    }

    private void bind() {
        Prism.padTopForStatusBar(root.findViewById(R.id.aboutContent));

        ((TextView) root.findViewById(R.id.aboutVersion)).setText(
                a.getString(R.string.about_version, BuildInfo.versionName(a)));
        ((TextView) root.findViewById(R.id.tvRepo)).setText(REPO_URL);
        Prism.press(root.findViewById(R.id.cardRepo), () -> openUrl(REPO_URL));

        LinearLayout list = root.findViewById(R.id.licenseList);
        LayoutInflater inflater = LayoutInflater.from(a);
        for (String[] dep : DEPS) {
            View row = inflater.inflate(R.layout.item_license, list, false);
            ((TextView) row.findViewById(R.id.licenseName)).setText(dep[0]);
            ((TextView) row.findViewById(R.id.licenseBadge)).setText(dep[1]);
            ((TextView) row.findViewById(R.id.licenseUse)).setText(dep[2]);
            final String url = dep[3];
            row.setOnClickListener(v -> openUrl(url));
            list.addView(row);
        }
    }

    private void openUrl(String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            try {
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, url);
                a.startActivity(Intent.createChooser(share, url));
            } catch (Throwable ignored) {
                Toast.makeText(a, url, Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(a, url, Toast.LENGTH_LONG).show();
        }
    }
}
