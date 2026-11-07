package io.github.lianxia233.privbroker;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 无线调试服务的 mDNS 探测。
 *
 * 同时发现配对服务与连接服务，把端口回调给调用方。放在独立类里而不是服务里，
 * 是因为端口发现不应依赖前台服务存活：通知被 ROM 屏蔽或服务被回收时，
 * 应用内输码路径仍要能拿到端口完成配对。
 *
 * 与参考实现一致，解析结果需通过 {@link MdnsFilter} 过滤，且服务消失时
 * 要把对应端口置回 -1 —— 配对成功后配对服务会撤下广播、连接服务会重分配端口，
 * 若继续沿用旧端口，连接阶段必然失败。
 */
public final class MdnsProbe {

    private static final String TAG = "MdnsProbe";

    public interface Callback {
        /** 任一端口就绪或变化时回调；未就绪的端口为 -1。 */
        void onPorts(int pairingPort, int connectPort);
    }

    private static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp";
    private static final String SERVICE_CONNECT = "_adb-tls-connect._tcp";

    private static MdnsProbe instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener pairingListener;
    private NsdManager.DiscoveryListener connectListener;
    private Callback callback;
    private boolean discovering;
    private int pairingPort = -1;
    private int connectPort = -1;
    private String pairingServiceName;
    private String connectServiceName;
    /** 最近一次 mDNS 解析出的地址，通常是 WLAN 接口 IP（adbd 的真实监听地址）。 */
    private volatile String resolvedHost;

    private MdnsProbe(Context c) {
        app = c.getApplicationContext();
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
    }

    public static synchronized MdnsProbe get(Context c) {
        if (instance == null) {
            instance = new MdnsProbe(c);
        }
        return instance;
    }

    public int pairingPort() {
        return pairingPort;
    }

    public int connectPort() {
        return connectPort;
    }

    /** mDNS 解析出的地址；可能为 null，此时连接层会枚举本机网卡自行补齐。 */
    public String resolvedHost() {
        return resolvedHost;
    }

    /** 开始发现；重复调用只更新回调。 */
    public synchronized void start(Callback cb) {
        callback = cb;
        if (discovering || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || nsd == null) {
            return;
        }
        discovering = true;
        pairingListener = makeListener(true);
        connectListener = makeListener(false);
        try {
            nsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener);
        } catch (Throwable t) {
            Log.w(TAG, "discover pairing service failed", t);
        }
        try {
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener);
        } catch (Throwable t) {
            Log.w(TAG, "discover connect service failed", t);
        }
    }

    public synchronized void stop() {
        callback = null;
        if (!discovering || nsd == null) {
            return;
        }
        discovering = false;
        try {
            nsd.stopServiceDiscovery(pairingListener);
        } catch (Throwable ignored) {
        }
        try {
            nsd.stopServiceDiscovery(connectListener);
        } catch (Throwable ignored) {
        }
    }

    /** 只断开回调，保持发现运行；Activity 销毁时用它避免泄漏，同时不打断发现。 */
    public synchronized void clearCallback() {
        callback = null;
    }

    /** 重新开始一轮发现（配对成功后连接端口会变化，必须重新解析）。 */
    public synchronized void restart(Callback cb) {
        stop();
        pairingPort = -1;
        connectPort = -1;
        pairingServiceName = null;
        connectServiceName = null;
        start(cb);
    }

    /** 只丢弃连接端口，用于配对成功后强制等待新的连接服务广播。 */
    public synchronized void invalidateConnect() {
        connectPort = -1;
        connectServiceName = null;
        if (callback != null) {
            callback.onPorts(pairingPort, connectPort);
        }
    }

    private NsdManager.DiscoveryListener makeListener(final boolean isPairing) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "start discovery failed: " + serviceType + " code=" + errorCode);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                final String name = serviceInfo == null ? null : serviceInfo.getServiceName();
                main.post(() -> onLost(isPairing, name));
            }

            @Override
            public void onServiceFound(final NsdServiceInfo serviceInfo) {
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        }

                        @Override
                        public void onServiceResolved(final NsdServiceInfo resolved) {
                            final int port = resolved.getPort();
                            final String name = resolved.getServiceName();
                            final String host = MdnsFilter.hostOf(resolved);
                            if (!MdnsFilter.accept(resolved, port)) {
                                return;
                            }
                            if (host != null) {
                                resolvedHost = host;
                            }
                            Log.d(TAG, "发现服务 " + name + " @ " + host + ":" + port);
                            main.post(() -> onPort(isPairing, port, name));
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "resolve service failed", t);
                }
            }
        };
    }

    private synchronized void onPort(boolean isPairing, int port, String name) {
        if (isPairing) {
            pairingPort = port;
            pairingServiceName = name;
        } else {
            connectPort = port;
            connectServiceName = name;
        }
        if (callback != null) {
            callback.onPorts(pairingPort, connectPort);
        }
    }

    private synchronized void onLost(boolean isPairing, String name) {
        if (isPairing) {
            if (name != null && pairingServiceName != null && !name.equals(pairingServiceName)) {
                return;
            }
            pairingPort = -1;
        } else {
            if (name != null && connectServiceName != null && !name.equals(connectServiceName)) {
                return;
            }
            connectPort = -1;
        }
        if (callback != null) {
            callback.onPorts(pairingPort, connectPort);
        }
    }
}
