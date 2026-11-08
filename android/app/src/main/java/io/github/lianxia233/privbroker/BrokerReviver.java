package io.github.lianxia233.privbroker;

import android.content.Context;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 特权代理自愈：静默安装/卸载发现代理不在运行时，尝试不经任何界面、
 * 无感地把它重新拉起来。
 *
 * <p>背景：代理是 shell(2000) 下的 app_process 常驻进程。即便已用 setsid
 * 脱离 adbd 会话，厂商 ROM（HyperOS/MIUI 的幻影进程监控、深度清理、lmkd）
 * 仍可能在激活后的任意时刻把它回收。主界面的状态徽章只反映查询瞬间，
 * 用户「看到已就绪、点安装却失败」正是这两次探测之间服务被杀造成的。
 * 与其在安装时直接报错，不如利用已保存的配对凭据自动恢复：无线调试开着、
 * 配对公钥还在的前提下，重拉与首次激活走同一条通道，无需重新输码。</p>
 *
 * <p>恢复步骤（全部后台、限时）：
 * <ol>
 *   <li>先实测端点（可能只是状态过期）——已连通直接成功；</li>
 *   <li>mDNS 发现 {@code _adb-tls-connect._tcp} 拿当前连接端口
 *       （无线调试每次开关端口都会变，不能用旧值）；</li>
 *   <li>复用激活通道 {@link WirelessRunner#start} 投递启动命令
 *       （内部含单实例清理与防幻影进程设置）；</li>
 *   <li>轮询 {@link BrokerClient#probe} 确认端点恢复监听。</li>
 * </ol>
 * 任一步失败立即返回原因，调用方决定降级提示。无线调试已关闭时第 2 步
 * 必然超时，这是最常见的失败形态，文案直接引导用户回激活页。</p>
 */
final class BrokerReviver {

    /** 等待 mDNS 解析出连接端口的时长。 */
    private static final long MDNS_WAIT_MS = 9000L;
    /** 投递启动命令后等待端点恢复监听的总时长。 */
    private static final long PROBE_TIMEOUT_MS = 12_000L;
    /** 探测轮询间隔。 */
    private static final long PROBE_INTERVAL_MS = 500L;

    private BrokerReviver() {
    }

    /**
     * 尝试自动恢复特权代理。返回 {@code null} 表示端点已恢复监听；
     * 否则返回面向用户的原因（供执行日志与失败通知使用）。
     * 阻塞式，耗时上限约 MDNS_WAIT_MS + PROBE_TIMEOUT_MS，只可在后台线程调用。
     */
    static String revive(Context c) {
        if (BrokerClient.probe(c)) {
            return null;
        }
        int port = discoverConnectPort(c);
        if (port <= 0) {
            return "未发现无线调试连接端口：「无线调试」可能已被关闭。"
                    + "请在系统开发者选项重新打开无线调试，再回应用点「激活服务」。";
        }
        String host = MdnsProbe.get(c).resolvedHost();
        try {
            // 只投递启动命令；是否真的恢复以 probe 实测为准，不信任通道返回值。
            WirelessRunner.start(c, port, host);
        } catch (Throwable t) {
            return "自动恢复命令投递失败：" + t;
        }
        long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (BrokerClient.probe(c)) {
                return null;
            }
            try {
                Thread.sleep(PROBE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return BrokerClient.probe(c) ? null
                : "恢复命令已投递但端点未恢复监听，请回应用「激活服务」查看启动日志。";
    }

    /**
     * 限时等待 mDNS 解析出 {@code _adb-tls-connect} 端口。复用全局
     * {@link MdnsProbe} 单例：先停掉可能存在的旧发现（激活页多半已不在
     * 前台），完成后同样停止，避免常驻 discovery。
     */
    private static int discoverConnectPort(Context c) {
        MdnsProbe probe = MdnsProbe.get(c);
        final CountDownLatch latch = new CountDownLatch(1);
        probe.stop();
        probe.start(new MdnsProbe.Callback() {
            @Override
            public void onPorts(int pairingPort, int connectPort) {
                if (connectPort > 0) {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(MDNS_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int port = probe.connectPort();
        probe.stop();
        return port;
    }
}
