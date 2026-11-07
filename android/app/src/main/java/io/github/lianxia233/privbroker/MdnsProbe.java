package io.github.lianxia233.privbroker;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 无线调试服务的 mDNS 探测。
 *
 * 同时发现配对服务与连接服务，把端口回调给调用方。放在独立类里而不是服务里，
 * 是因为端口发现不应依赖前台服务存活：通知被 ROM 屏蔽或服务被回收时，
 * 应用内输码路径仍要能拿到端口完成配对。
 */
public final class MdnsProbe {

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
        } catch (Throwable ignored) {
        }
        try {
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener);
        } catch (Throwable ignored) {
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

    /** 重新开始一轮发现（配对成功后连接端口可能变化）。 */
    public synchronized void restart(Callback cb) {
        stop();
        pairingPort = -1;
        connectPort = -1;
        start(cb);
    }

    private NsdManager.DiscoveryListener makeListener(final boolean isPairing) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
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
                            if (port <= 0) {
                                return;
                            }
                            main.post(() -> onPort(isPairing, port));
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private synchronized void onPort(boolean isPairing, int port) {
        if (isPairing) {
            pairingPort = port;
        } else {
            connectPort = port;
        }
        if (callback != null) {
            callback.onPorts(pairingPort, connectPort);
        }
    }
}
