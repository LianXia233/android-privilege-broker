package io.github.lianxia233.privbroker;

import android.app.Activity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 页面与 Rust 核心之间的统一调用入口。
 *
 * <p>所有页面（仪表盘 / 记录 / 设置 / 关于）共用两条后台线程：一条负责
 * {@code RustCore.dispatch} 的阻塞调用，一条只做 UI 回投。界面层不再各自
 * 维护线程池，避免「面板切换后旧线程还在回调已销毁视图」。</p>
 */
final class PageBridge {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private PageBridge() {
    }

    /** 异步调用 Rust 核心；回调总在主线程，且 Activity 已销毁时丢弃。 */
    static void call(Activity a, String method, String args, Consumer<JSONObject> cb) {
        IO.execute(() -> {
            JSONObject result = dispatch(method, args);
            if (a == null || a.isFinishing() || a.isDestroyed()) {
                return;
            }
            a.runOnUiThread(() -> {
                if (!a.isFinishing() && !a.isDestroyed()) {
                    cb.accept(result);
                }
            });
        });
    }

    /** 同线程版本，供已在后台线程的代码直接使用。 */
    static JSONObject dispatch(String method, String args) {
        try {
            return new JSONObject(RustCore.dispatch(method, args));
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            // 本地调试包只跑 Gradle、不带 Rust .so：RustCore 的静态初始化会失败，
            // 这里收敛成明确错误，避免界面把「安装包缺原生库」误报成代理异常。
            try {
                return new JSONObject().put("ok", false)
                        .put("error", new JSONObject()
                                .put("kind", "CORE_MISSING")
                                .put("message", "安装包缺少 libinstaller_core.so，原生核心未加载"));
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        } catch (Throwable t) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", new JSONObject().put("message", String.valueOf(t)));
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }
    }

    /** 增量写配置：Rust 侧按补丁合并，不会覆盖未提交的字段。 */
    static void saveConfig(Activity a, JSONObject patch) {
        final String args;
        try {
            args = new JSONObject().put("config", patch).toString();
        } catch (Throwable t) {
            return;
        }
        call(a, "setConfig", args, r -> {
            if (!r.optBoolean("ok") && a != null) {
                JSONObject e = r.optJSONObject("error");
                android.widget.Toast.makeText(a,
                        e == null ? "保存失败" : e.optString("message", "保存失败"),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
}
