package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 外部「打开方式 / 分享 / 安装 / 卸载」意图的入口。
 *
 * 双路径窗口策略（2026-11-07 第二次实机定位后修正）：
 *  - 普通调用（无需等待结果）：保持原来的零窗口语义——onCreate 内立即
 *    finish，框架因 mFinished=true 跳过 wm.addView，代码完全不碰 Window。
 *  - 等待结果的调用（{@code startActivityForResult} / {@code EXTRA_RETURN_RESULT}）：
 *    改用 {@code @style/SilentEntryTheme} 并驻留 {@link #RESULT_DELAY_MS} 毫秒，
 *    期间窗口被限制为 1x1、NOT_FOCUSABLE、NOT_TOUCHABLE、无 dim、系统栏透明，
 *    不遮挡、不抢焦点、不吞触摸；到期后再 setResult + finish。
 *
 * 为什么要延迟（真正的根因）：
 *  Mihon/Tachiyomi 的 {@code ExtensionInstallActivity} 在 MIUI 设备上
 *  （{@code hasMiuiPackageInstaller=true}）会在 onCreate 设置
 *  {@code ignoreResult=true} 与 {@code ignoreUntil = now + 1s}；其
 *  {@code onActivityResult} 若在 1 秒内到达，直接
 *  {@code hasIgnoredResult=true; return} —— 不调用 finish()，且判断发生在
 *  resultCode 之前，RESULT_OK / RESULT_CANCELED 一律被吞。它只能靠后续
 *  {@code onStart()} 兜底 finish，而零窗口安装器 2ms 内就结束，调用方
 *  在结果派发时早已走完 onStart/onResume，此后再无 onStart，
 *  透明中转窗口便永久停在栈顶（黑系统栏、触摸失效、需按返回键）。
 *  把结果回推到 1 秒忽略窗口之后，onActivityResult 就会走
 *  {@code checkInstallationResult(resultCode); finish()} 的正常分支。
 *
 * 后台安装任务始终在 onCreate 立即启动，延迟只作用于「结果回传」，
 * 不影响安装开始时间、通知与进度。
 */
public final class SilentInstallActivity extends Activity {
    private static final String TAG = "SilentEntry";
    static final String EXTRA_URIS = "staged_uris";
    /** 调用方给的原始 URI（删除安装包用），与 EXTRA_URIS 里的私有副本区分开。 */
    static final String EXTRA_SOURCES = "staged_sources";
    static final String EXTRA_CALLER = "caller";
    static final String EXTRA_CALLER_LABEL = "caller_label";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_UNINSTALL = "uninstall";
    static final String EXTRA_UNINSTALL_PKG = "uninstall_pkg";
    /** 回传给调用方的结果说明，仅用于调用方自查，不含任何 URI 或用户数据。 */
    private static final String EXTRA_RESULT_DETAIL = "silent_result_detail";

    /**
     * 调用方 MIUI workaround 的忽略窗口是 1 秒（System.nanoTime 单调时钟）。
     * 取 1400ms 留出 MIUI 调度与 Activity 启动的余量，确保 onActivityResult
     * 一定落在 ignoreUntil 之后。
     */
    private static final long RESULT_DELAY_MS = 1400L;

    private final List<String> uris = new ArrayList<>();
    /** 调用方给的原始 URI，供「安装成功后删除安装包」使用。 */
    private final List<String> originals = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable finishRunnable = this::finishWithResult;
    private boolean valid;
    private boolean uninstall;
    private boolean ended;
    private boolean delayedResult;
    private long createdAtMs;
    private String uninstallPkg = "";
    private String label = "安装包";
    private String caller = "@unknown";
    private String callerLabel = "";
    /** 默认取消：任何未走到 ALLOW 的路径都必须给调用方一个明确回调。 */
    private int resultCode = RESULT_CANCELED;
    private String resultDetail = "NOT_HANDLED";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdAtMs = SystemClock.uptimeMillis();
        delayedResult = callerWaitsForResult();
        if (delayedResult) {
            configureWindow();
            // 部分 ROM 在 makeVisible() 时依据主题重算 LayoutParams，
            // 窗口 addView 完成后的下一帧再申明一次属性。
            getWindow().getDecorView().post(this::configureWindow);
        }
        try {
            Notifier.ensureChannels(this);
            parseIntent(getIntent());
            if (valid) {
                // 外部 content:// 的读权限只在本 Activity 存活期间有效，
                // 必须趁 onCreate 阶段复制到自家 StagedProvider。
                if (!uninstall) {
                    // 原始 URI 单独留一份：安装成功后「删除安装包」要删的是它，
                    // 而不是本应用缓存里的私有副本。
                    originals.addAll(uris);
                    List<String> staged = StagedFiles.stage(this, uris);
                    uris.clear();
                    uris.addAll(staged);
                }
                caller = resolveCaller(getIntent());
                evaluate();
            }
        } catch (Throwable t) {
            Log.w(TAG, "silent entry failed", t);
            resultCode = RESULT_CANCELED;
            resultDetail = "ENTRY_ERROR";
        } finally {
            scheduleResult();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (delayedResult) {
            configureWindow();
        }
    }

    @Override
    public void onBackPressed() {
        // 极端情况下 ROM 仍把返回键送给本窗口：也要保证结果被回传。
        finishWithResult();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(finishRunnable);
    }

    // ---------------------------------------------------------------- 窗口与结果

    /** 只有明确等待结果的调用方才需要短窗口驻留，其余保持零窗口。 */
    private boolean callerWaitsForResult() {
        Intent i = getIntent();
        boolean extra = i != null && i.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false);
        return extra || getCallingActivity() != null;
    }

    /**
     * 把短窗口压到用户完全无感：1x1、左上角、不聚焦、不可触摸、无 dim，
     * 背景与系统栏全部透明并保留 DRAWS_SYSTEM_BAR_BACKGROUNDS，
     * 避免系统按黑色 taskDescription 绘制状态栏/导航栏。
     */
    private void configureWindow() {
        try {
            Window w = getWindow();
            if (w == null) {
                return;
            }
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = 1;
            lp.height = 1;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            lp.dimAmount = 0f;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            w.setAttributes(lp);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setDimAmount(0f);
            View decor = w.getDecorView();
            decor.setBackgroundColor(Color.TRANSPARENT);
            decor.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "configureWindow failed", t);
        }
    }

    private void scheduleResult() {
        if (!delayedResult) {
            finishWithResult();
            return;
        }
        Log.i(TAG, "result delayed " + RESULT_DELAY_MS + "ms"
                + " code=" + resultCode + " detail=" + resultDetail
                + " resultTo=" + safeCallingActivity());
        mainHandler.postDelayed(finishRunnable, RESULT_DELAY_MS);
    }

    /**
     * 所有路径的唯一收口：先回传结果，再 finish。
     * 绝不调用 overridePendingTransition 等窗口转场 API。
     */
    private void finishWithResult() {
        if (ended) {
            return;
        }
        ended = true;
        mainHandler.removeCallbacks(finishRunnable);
        try {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_DETAIL, resultDetail);
            setResult(resultCode, data);
        } catch (Throwable t) {
            Log.w(TAG, "setResult failed", t);
        }
        Log.i(TAG, "finish result=" + resultCode + " detail=" + resultDetail
                + " caller=" + caller + " resultTo=" + safeCallingActivity()
                + " elapsedMs=" + (SystemClock.uptimeMillis() - createdAtMs));
        finish();
    }

    // ---------------------------------------------------------------- 意图解析

    private void parseIntent(Intent i) {
        if (i == null || i.getAction() == null) {
            return;
        }
        String action = i.getAction();
        if (Intent.ACTION_DELETE.equals(action)
                || "android.intent.action.UNINSTALL_PACKAGE".equals(action)) {
            Uri u = i.getData();
            String pkg = u == null ? "" : u.getSchemeSpecificPart();
            if (pkg.startsWith("package:")) {
                pkg = pkg.substring("package:".length());
            }
            pkg = pkg.trim();
            if (pkg.isEmpty()) {
                return;
            }
            uninstall = true;
            uninstallPkg = pkg;
            String installed = AppNames.labelForPackage(this, pkg);
            label = installed == null ? pkg : installed;
            valid = true;
            return;
        }

        addUri(i.getData());
        Uri stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
        addUri(stream);
        ArrayList<Uri> extras = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (extras != null) {
            for (Uri u : extras) {
                addUri(u);
            }
        }
        ClipData clip = i.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) {
                addUri(clip.getItemAt(k).getUri());
            }
        }
        if (uris.isEmpty()) {
            return;
        }
        // 社交软件转发时会把 xxx.apk 改成 xxx.apk.1；归一化后再作为显示名，
        // 通知与日志里不会出现多余的尾巴。
        String name = FileNames.normalize(AppNames.displayName(this, uris.get(0)));
        label = name.isEmpty() ? "安装包" : name;
        valid = true;
    }

    private void addUri(Uri u) {
        if (u == null || uris.contains(u.toString()) || !plausibleInstallSource(u)) {
            return;
        }
        uris.add(u.toString());
    }

    /**
     * 系统分享的 SEND filter 用全类型通配 MIME 兜底后，任何类型的文件都可能被送进来。
     * 这里按显示名做一次轻量过滤：明确是图片 / 文本 / 音视频 / 文档的扩展名
     * 直接丢弃，避免误点分享就发一条「安装失败」通知；拿不到扩展名（很多
     * content:// 只给数字 ID）时放行，交给 Rust 按文件内容判定，不误拒 APK。
     */
    private boolean plausibleInstallSource(Uri u) {
        // 先剥掉 .1 / .bin 这类社交软件追加的后缀，xxx.apk.1 才能被识别成
        // 安装包；不剥的话既不在白名单、也不在无关黑名单里，虽会默认放行，
        // 但后续按显示名做类型判断时会走错分支。
        String name = FileNames.normalize(rawDisplayName(u));
        if (FileNames.isInstallable(name)) {
            return true;
        }
        String low = name.toLowerCase(Locale.ROOT);
        String[] unrelated = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic",
                ".txt", ".pdf", ".mp3", ".m4a", ".mp4", ".mkv", ".avi", ".mov",
                ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".html", ".htm",
                ".json", ".xml", ".log", ".vcf", ".ics", ".torrent"};
        for (String ext : unrelated) {
            if (low.endsWith(ext)) {
                return false;
            }
        }
        return true;
    }

    private String rawDisplayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (n >= 0 && !c.isNull(n)) {
                    String s = c.getString(n);
                    if (s != null) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String seg = u.getLastPathSegment();
        return seg == null ? "" : seg;
    }

    /** 尽力识别是谁发起的调用；拿不到一律视为匿名来源，必须每次询问。 */
    private String resolveCaller(Intent i) {
        String self = getPackageName();
        List<String> candidates = new ArrayList<>();
        // startActivityForResult 场景下这是最可靠、由框架背书的来源。
        ComponentName callingActivity = getCallingActivity();
        if (callingActivity != null) {
            candidates.add(callingActivity.getPackageName());
        }
        String calling = getCallingPackage();
        if (calling != null) {
            candidates.add(calling);
        }
        try {
            Uri ref = getReferrer();
            if (ref != null) {
                candidates.add(packageFromReferrer(ref));
            }
        } catch (Throwable ignored) {
        }
        try {
            Uri extra = i.getParcelableExtra(Intent.EXTRA_REFERRER);
            if (extra != null) {
                candidates.add(packageFromReferrer(extra));
            }
        } catch (Throwable ignored) {
        }
        String refName = i.getStringExtra(Intent.EXTRA_REFERRER_NAME);
        if (refName != null && !refName.isEmpty()) {
            try {
                candidates.add(packageFromReferrer(Uri.parse(refName)));
            } catch (Throwable ignored) {
            }
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && !c.equals(self) && plausiblePackage(c)) {
                return c;
            }
        }
        return "@unknown";
    }

    private String packageFromReferrer(Uri u) {
        if (u == null) {
            return null;
        }
        String scheme = u.getScheme();
        if ("android-app".equals(scheme) || "app".equals(scheme)) {
            return u.getHost();
        }
        return null;
    }

    private boolean plausiblePackage(String s) {
        if (s.length() < 3 || s.length() > 128) {
            return false;
        }
        int dots = 0;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '.') {
                dots++;
            } else if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        return dots >= 1;
    }

    // ---------------------------------------------------------------- 授权决策

    private void evaluate() {
        JSONObject r;
        try {
            r = call("callerDecision",
                    new JSONObject().put("caller", caller).toString());
        } catch (Throwable t) {
            r = new JSONObject();
        }
        JSONObject d = r.optJSONObject("data");
        if (d != null) {
            caller = d.optString("caller", caller);
            callerLabel = d.optString("callerLabel", "");
        }
        if (callerLabel == null || callerLabel.trim().isEmpty()) {
            callerLabel = AppNames.labelForCaller(this, caller);
        }
        String decision = d == null ? "ASK" : d.optString("decision", "ASK");
        Log.i(TAG, "decision=" + decision + " caller=" + caller
                + " resultTo=" + safeCallingActivity() + " valid=" + valid);

        ArrayList<String> staged = new ArrayList<>(uris);
        ArrayList<String> sources = new ArrayList<>(originals);
        if ("DENY".equals(decision)) {
            DenyLog.record(App.context(), uninstall, label, caller, callerLabel);
            resultCode = RESULT_CANCELED;
            resultDetail = "CALLER_DENIED";
        } else if ("ASK".equals(decision)) {
            DenyLog.record(App.context(), uninstall, label, caller, callerLabel, "ask");
            // 首次授权直接在当前应用上方弹卡片，用户不必切回本应用。
            // 拿不到悬浮窗权限时 AuthPrompt 会自行回退成「待授权」通知。
            boolean handled = AuthPrompt.show(App.context(), label, caller, callerLabel,
                    staged, sources, uninstall, uninstallPkg);
            if (!handled) {
                Notifier.authPending(App.context(), label, caller, callerLabel,
                        staged, sources, uninstall, uninstallPkg);
            }
            resultCode = RESULT_CANCELED;
            resultDetail = "CALLER_PENDING";
        } else {
            // 把原始 URI 一并交下去：开启「成功后删除安装包」时删的是调用方的
            // 原文件，而不是本应用缓存里的私有副本。
            TaskLauncher.launch(this, uninstall, staged, sources, uninstallPkg, caller, label);
            resultCode = RESULT_OK;
            resultDetail = "ACCEPTED";
        }
    }

    private JSONObject call(String method, String args) {
        try {
            return new JSONObject(RustCore.dispatch(method, args));
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", new JSONObject().put("message", String.valueOf(e)));
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private String safeCallingActivity() {
        ComponentName c = getCallingActivity();
        return c == null ? "none" : c.flattenToShortString();
    }
}
