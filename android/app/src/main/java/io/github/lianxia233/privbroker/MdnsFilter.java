package io.github.lianxia233.privbroker;

import android.net.nsd.NsdServiceInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Enumeration;

/**
 * mDNS 解析结果的合法性过滤。
 *
 * 系统服务发现会 Advertising 局域网内所有同名服务，也会缓存已经消失的记录。
 * 若不加以甄别，拿到的端口可能是别的设备的、或早已失效的，后续连接必然失败。
 * 参考成熟实现，这里保留两道过滤：
 *
 * 1. 端口必须已被本机占用：尝试在 127.0.0.1 上 bind 一次，bind 失败
 *    （端口已被 adbd 监听）才认为有效；bind 成功说明端口空闲、服务其实不在。
 * 2. 解析出的地址若存在，必须属于本机某个网卡，排除局域网里其它设备广播的
 *    `_adb-tls-pairing` / `_adb-tls-connect`。地址缺失时只保留第 1 道兜底。
 */
final class MdnsFilter {

    private MdnsFilter() {
    }

    static boolean accept(NsdServiceInfo info, int port) {
        if (port <= 0 || info == null) {
            return false;
        }
        if (!isPortListening(port)) {
            return false;
        }
        InetAddress host = info.getHost();
        return host == null || isLocalAddress(host);
    }

    /**
     * 与参考实现同名方法语义一致：返回 true 表示「该端口可连接」，
     * 判断依据是本机已有服务在监听（bind 抛 IOException）。
     */
    private static boolean isPortListening(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", port), 1);
            return false;
        } catch (IOException e) {
            return true;
        } catch (Throwable ignored) {
            // 个别 ROM 对 ServerSocket 有限制，无法判定时不额外拦截
            return true;
        }
    }

    private static boolean isLocalAddress(InetAddress host) {
        String target = normalize(host.getHostAddress());
        if (target == null) {
            return true;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    String candidate = normalize(addresses.nextElement().getHostAddress());
                    if (target.equals(candidate)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    /** 去掉 IPv6 的 scope 后缀（如 fe80::1%wlan0），否则字符串比较永远不相等。 */
    private static String normalize(String address) {
        if (address == null) {
            return null;
        }
        int percent = address.indexOf('%');
        return percent > 0 ? address.substring(0, percent) : address;
    }
}
