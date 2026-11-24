package io.github.lianxia233.privbroker;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 特权保活：借软件自带的无线调试授权（特权代理以 shell uid 2000 运行）
 * 把本应用放进系统的「不受限制」名单。
 *
 * <p>只做三件 shell 侧才能做的事，全部幂等、失败静默：
 * <ul>
 *   <li>{@code cmd deviceidle whitelist +pkg}：电池优化白名单；</li>
 *   <li>{@code am set-standby-bucket pkg active}：App Standby 置为活跃；</li>
 *   <li>{@code cmd appops set pkg RUN_ANY_IN_BACKGROUND allow}：允许后台运行；</li>
 *   <li>{@code am set-inactive pkg false}：撤销「闲置」标记。</li>
 * </ul>
 * 命令走 argv + 位置参数，包名不参与命令拼接。</p>
 */
final class Keeper {
    private static final String TAG = "Keeper";
    private static volatile long lastRun;

    private Keeper() {
    }

    /** 幂等应用；失败只写 logcat，不影响任何业务。 */
    static void ensure(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastRun < 60_000L) {
            return;
        }
        lastRun = now;
        final String pkg = c.getPackageName();
        Thread t = new Thread(() -> {
            try {
                String script = "cmd deviceidle whitelist +$1 >/dev/null 2>&1;"
                        + "am set-standby-bucket $1 active >/dev/null 2>&1;"
                        + "cmd appops set $1 RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1;"
                        + "am set-inactive $1 false >/dev/null 2>&1;"
                        + "echo done";
                JSONArray argv = new JSONArray()
                        .put("sh").put("-c").put(script).put("sh").put(pkg);
                JSONObject r = new JSONObject(BrokerBridge.call("exec",
                        new JSONObject().put("argv", argv).put("timeoutMs", 30_000L)
                                .toString()));
                Log.i(TAG, "keepalive code=" + r.optInt("code", -1));
            } catch (Throwable e) {
                Log.w(TAG, "keepalive skipped", e);
            }
        }, "privileged-keeper");
        t.setDaemon(true);
        t.start();
    }
}
