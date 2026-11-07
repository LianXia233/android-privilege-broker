package io.github.lianxia233.privbroker;

import android.net.nsd.NsdServiceInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * mDNS 解析结果的地址处理。
 *
 * 关键点（真机 REDMI K80 / HyperOS 实测）：无线调试的配对服务与连接服务
 * 绑定在 <b>WLAN 接口 IP</b>（如 {@code 192.168.88.158:39099}），并不绑定
 * loopback。参考实现按 AOSP 行为用 {@code 127.0.0.1} 判断端口是否被占用，
 * 在这类 ROM 上会把每一个服务都判成「端口空闲」而全部丢弃 —— 表现为
 * 「无法自动识别」。因此这里不再做端口占用猜测，只保留地址有效性判断，
 * 能否连通交由后续 TCP 连接实测。
 *
 * 参考实现的另一道过滤（解析地址须属于本机网卡）同样放宽：地址缺失、
 * 枚举不到网卡、或地址看起来不属于本机时都不再直接丢弃服务，而是把
 * mDNS 地址、本机全部网卡 IPv4 与 loopback 一起作为候选交给连接层逐个尝试。
 * 这样既能排除局域网里其它设备广播的同名服务（连不上就不会被误用），
 * 也不会因为 ROM 返回的地址表示形式不同而漏掉真正的本机服务。
 */
final class MdnsFilter {

    private MdnsFilter() {
    }

    /** 只做最基本的合法性判断，不做任何端口占用探测。 */
    static boolean accept(NsdServiceInfo info, int port) {
        return port > 0 && info != null;
    }

    /** 取出 mDNS 解析出的地址（可能是 WLAN IP、IPv6 或 null）。 */
    static String hostOf(NsdServiceInfo info) {
        if (info == null) {
            return null;
        }
        InetAddress host = info.getHost();
        return host == null ? null : normalize(host.getHostAddress());
    }

    /**
     * 生成连接候选地址，按可靠性排序并去重：
     * 1) mDNS 解析出的地址（真机上通常就是 WLAN IP）；
     * 2) 本机所有网卡的非回环 IPv4；
     * 3) {@code 127.0.0.1}（AOSP 原生行为，兜底）。
     */
    static String[] candidates(String mdnsHost) {
        Set<String> ordered = new LinkedHashSet<>();
        add(ordered, mdnsHost);
        for (String local : localIPv4()) {
            add(ordered, local);
        }
        add(ordered, "127.0.0.1");
        return ordered.toArray(new String[0]);
    }

    private static void add(Set<String> set, String value) {
        String s = normalize(value);
        if (s != null && !s.isEmpty() && !"0.0.0.0".equals(s)) {
            set.add(s);
        }
    }

    private static List<String> localIPv4() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        result.add(normalize(address.getHostAddress()));
                    }
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败时仍保留 mDNS 地址与 loopback 兜底
        }
        return result;
    }

    /** 去掉 IPv6 的 scope 后缀（如 fe80::1%wlan0），避免字符串比较与连接失败。 */
    private static String normalize(String address) {
        if (address == null) {
            return null;
        }
        int percent = address.indexOf('%');
        return percent > 0 ? address.substring(0, percent) : address;
    }
}
