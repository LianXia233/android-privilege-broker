package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 静默安装授权页。
 *
 * 它现在是**普通、不透明、全屏的页面**，而不是 Dialog：
 * Dialog 窗口在部分 ROM 上会被铺成整屏半透明背板，用户看到的就是「一层遮罩弹窗、
 * 屏幕点不动」。普通页面有自己的不透明窗口和 {@code @color/window_bg} 背景，
 * 不存在任何浮层，不遮挡前台应用，也不会抢走焦点。
 *
 * 更重要的是**它不在安装链路上**：静默入口遇到未授权调用时不会启动任何界面，
 * 只发一条通知；用户点通知才会来到这里。因此安装过程中屏幕上不可能出现
 * 由本应用创建的窗口。
 *
 * 两种进入方式：
 *  - 从通知进入（无待安装 URI）：只做授权决定，并提示回到原应用重新发起；
 *  - 带 URI 进入（兼容旧路径）：授权后直接交给后台任务。
 */
public final class CallerAuthActivity extends Activity {
    private List<String> uris = new ArrayList<>();
    /** 调用方给的原始 URI，仅用于「安装成功后删除安装包」。 */
    private List<String> sources = new ArrayList<>();
    private boolean uninstall;
    private boolean ended;
    private String uninstallPkg = "";
    private String label = "安装包";
    private String caller = "@unknown";
    private String callerLabel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);
        readExtras(getIntent());

        String who = callerLabel.trim().isEmpty()
                ? getString(R.string.caller_unknown) : callerLabel.trim();
        String message = uninstall
                ? getString(R.string.auth_dialog_uninstall, who)
                : getString(R.string.auth_dialog_install, who);
        if (uris.isEmpty()) {
            // 从通知进入：本次安装已被拦截，这里只负责授权
            message = message + "\n\n" + getString(R.string.auth_page_pending);
        }
        ((TextView) findViewById(R.id.authMessage)).setText(message);
        ((TextView) findViewById(R.id.authCaller)).setText(
                "@unknown".equals(caller) ? getString(R.string.caller_unknown) : caller);

        CheckBox remember = findViewById(R.id.authRemember);
        // 匿名来源不能被永久记住：否则一次允许会放行后续所有匿名调用
        if ("@unknown".equals(caller)) {
            remember.setEnabled(false);
            remember.setChecked(false);
        } else {
            // 从通知主动进来通常就是要长期放行，默认勾选，减少一次点击
            remember.setChecked(uris.isEmpty());
        }
        findViewById(R.id.btnAllow).setOnClickListener(
                v -> onDecision(true, remember.isChecked()));
        findViewById(R.id.btnDeny).setOnClickListener(
                v -> onDecision(false, remember.isChecked()));
    }

    private void readExtras(android.content.Intent i) {
        if (i == null) {
            return;
        }
        ArrayList<String> list = i.getStringArrayListExtra(SilentInstallActivity.EXTRA_URIS);
        if (list != null) {
            uris = list;
        }
        ArrayList<String> originals = i.getStringArrayListExtra(
                SilentInstallActivity.EXTRA_SOURCES);
        if (originals != null) {
            sources = originals;
        }
        caller = i.getStringExtra(SilentInstallActivity.EXTRA_CALLER);
        callerLabel = i.getStringExtra(SilentInstallActivity.EXTRA_CALLER_LABEL);
        label = i.getStringExtra(SilentInstallActivity.EXTRA_LABEL);
        uninstall = i.getBooleanExtra(SilentInstallActivity.EXTRA_UNINSTALL, false);
        uninstallPkg = i.getStringExtra(SilentInstallActivity.EXTRA_UNINSTALL_PKG);
        if (caller == null || caller.isEmpty()) {
            caller = "@unknown";
        }
        if (callerLabel == null) {
            callerLabel = "";
        }
        if (label == null || label.isEmpty()) {
            label = "安装包";
        }
        if (uninstallPkg == null) {
            uninstallPkg = "";
        }
        if (callerLabel.trim().isEmpty()) {
            callerLabel = AppNames.labelForCaller(this, caller);
        }
    }

    private void onDecision(boolean allow, boolean remember) {
        if (ended) {
            return;
        }
        ended = true;
        final boolean rememberFinal = remember && !"@unknown".equals(caller);
        final String callerFinal = caller;
        App.executor().execute(() -> {
            try {
                JSONObject a = new JSONObject()
                        .put("caller", callerFinal)
                        .put("remember", rememberFinal);
                RustCore.dispatch(allow ? "callerGrant" : "callerDeny", a.toString());
            } catch (Throwable ignored) {
            }
        });
        if (!allow) {
            DenyLog.record(getApplicationContext(), uninstall, label, caller, callerLabel);
        } else if (!uris.isEmpty() || uninstall) {
            // 只有真的带着待安装包时才继续安装；从通知进入没有包，仅完成授权
            TaskLauncher.launch(this, uninstall, uris, sources, uninstallPkg, caller, label);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        onDecision(false, false);
    }
}
