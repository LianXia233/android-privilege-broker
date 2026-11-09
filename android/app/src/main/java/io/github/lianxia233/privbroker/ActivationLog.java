package io.github.lianxia233.privbroker;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「激活服务」的记录落库与启动日志人话化。
 *
 * 之前结果条把原始启动日志从头部截到 260 字符，恰好把最关键的失败行
 * （如 {@code 监听失败: Address already in use (os error 98)}）切掉，用户只能
 * 看到一个省略号，无从诊断。这里改为两条职责：
 *
 * 1. 每次激活把**完整**原始日志追加进「执行日志」，界面只显示摘要；
 * 2. 把 shell 侧日志去重、只保留最后一次启动段、翻译为可读要点，并把完整
 *    失败原因单独提出来，供结果条与通知栏显示，不再把 uid / backend /
 *    selinux / abstract 命名等内部字段原样倒给用户。
 */
final class ActivationLog {

    static final String OP_ACTIVATE = "activate";
    static final String SOURCE_WIRELESS = "wireless";

    /** native 在 bind 成功后打印的权威标记（与 broker-wireless 常量一致）。 */
    private static final String MARK_LISTENING = "监听成功:";
    private static final String MARK_ALREADY_RUNNING = "特权代理已在运行";

    private ActivationLog() {
    }

    /**
     * 解析 native 返回结果：启动日志是否出现「服务已监听 / 已有实例在运行」。
     * 仅用于失败诊断（区分「进程没起来」与「起来了但 app 连不上」），
     * 不再是激活成功的判据 —— 成功一律以 {@link BrokerClient#handshake} 完整握手为准。
     * 只探端口不够：陈旧实例占着派生端点时端口探测恒为真，会把「在监听却拒绝当前
     * 令牌」误判成一次成功激活。
     */
    static boolean startLogConfirmsRunning(String rawJson) {
        try {
            JSONObject j = new JSONObject(rawJson);
            if (j.optBoolean("listening", false) || j.optBoolean("alreadyRunning", false)) {
                return true;
            }
            String out = j.optString("output", "");
            return out.contains(MARK_LISTENING) || out.contains(MARK_ALREADY_RUNNING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 本次是否为「复用已有实例」的幂等重复激活。 */
    static boolean reusedExistingInstance(String rawJson) {
        try {
            JSONObject j = new JSONObject(rawJson);
            if (j.optBoolean("alreadyRunning", false)) {
                return true;
            }
            return j.optString("output", "").contains(MARK_ALREADY_RUNNING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 追加一条激活结果记录。日志写入失败绝不影响激活流程。 */
    static void record(Context c, boolean ok, int port, String host, String code,
                       String summary, String rawOutput, long durationMs) {
        try {
            JSONObject e = new JSONObject();
            e.put("op", OP_ACTIVATE);
            e.put("source", SOURCE_WIRELESS);
            e.put("appName", c.getString(R.string.log_activate_title));
            e.put("ok", ok);
            if (code != null && !code.isEmpty()) {
                e.put("code", code);
            }
            e.put("port", port);
            if (host != null && !host.isEmpty()) {
                e.put("host", host);
            }
            e.put("durationMs", Math.max(0L, durationMs));

            StringBuilder detail = new StringBuilder();
            if (summary != null && !summary.isEmpty()) {
                detail.append(summary);
            }
            String clean = dedupe(rawOutput);
            if (!clean.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append('\n');
                }
                detail.append(c.getString(R.string.log_boot_log_head)).append('\n').append(clean);
            }
            e.put("detail", detail.toString());
            InstallLogStore.append(c, e);
        } catch (Throwable ignored) {
            // 日志是辅助能力，失败不影响主流程
        }
    }

    /**
     * 去掉连续重复行，并只保留最后一次启动段。
     * 自动激活与手动激活并发时，启动日志会被串成两遍完整的序列，这里收敛成一份。
     */
    static String dedupe(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        List<String> kept = new ArrayList<>();
        int lastStart = -1;
        String prev = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.contains("特权代理进程已拉起")) {
                lastStart = kept.size();
            }
            if (t.equals(prev)) {
                continue;
            }
            kept.add(t);
            prev = t;
        }
        if (lastStart > 0) {
            kept = new ArrayList<>(kept.subList(lastStart, kept.size()));
        }
        return String.join("\n", kept);
    }

    /** 提取完整失败原因行（不截断）；倒序取最后一条明确失败。 */
    static String failureReason(String raw) {
        String clean = dedupe(raw);
        if (clean.isEmpty()) {
            return "";
        }
        String[] lines = clean.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i];
            // 「已在运行」不是失败：它是幂等重复激活的正常结果。
            if (l.contains(MARK_ALREADY_RUNNING) || l.contains(MARK_LISTENING)) {
                continue;
            }
            if (l.contains("监听失败") || l.contains("特权代理启动失败")
                    || l.contains("无法加载 native") || l.contains("加载 native 库失败")
                    || l.startsWith("错误:") || l.contains("UnsatisfiedLinkError")
                    || l.contains("ClassNotFoundException") || l.contains("Exception")
                    || l.contains("Error:")) {
                return l;
            }
        }
        return "";
    }

    /** 把技术失败行翻译成人话，但完整保留原始错误内容（errno / 异常信息）。 */
    static String humanize(Context c, String line) {
        String rest;
        if (line.startsWith("监听失败:")) {
            rest = line.substring("监听失败:".length()).trim();
            // 这类 errno 在旧版本里意味着「旧进程仍占着端点」。修复后应变成
            // 幂等复用，但保留识别，避免历史日志被误读成致命故障。
            if (rest.contains("Address already in use") || rest.contains("os error 98")) {
                return c.getString(R.string.pairing_diag_reason_addr_in_use);
            }
            return c.getString(R.string.pairing_diag_reason_bind, rest);
        }
        if (line.startsWith("特权代理启动失败:")) {
            rest = line.substring("特权代理启动失败:".length()).trim();
            return c.getString(R.string.pairing_diag_reason_start, rest);
        }
        if (line.contains("无法加载 native") || line.contains("加载 native 库失败")) {
            return c.getString(R.string.pairing_diag_reason_native, line.trim());
        }
        if (line.startsWith("错误:")) {
            rest = line.substring("错误:".length()).trim();
            return c.getString(R.string.pairing_diag_reason_error, rest);
        }
        return line.trim();
    }

    /** 生成给结果条 / 通知栏显示的简洁摘要：结论 + 完整原因 + 过程要点。 */
    static String summarize(Context c, String raw, boolean serviceUp) {
        if (serviceUp) {
            return c.getString(R.string.pairing_activate_ok);
        }
        StringBuilder sb = new StringBuilder(c.getString(R.string.pairing_activate_failed));
        String reason = failureReason(raw);
        if (!reason.isEmpty()) {
            sb.append('\n').append(c.getString(R.string.pairing_diag_reason,
                    humanize(c, reason)));
        }
        String clean = dedupe(raw);
        List<String> steps = new ArrayList<>();
        if (clean.contains("特权代理进程已拉起")) {
            steps.add(c.getString(R.string.pairing_diag_loaded));
        }
        if (clean.contains("特权代理启动:")) {
            steps.add(c.getString(R.string.pairing_diag_uid_ok));
        }
        if ((clean.contains("监听成功:") || clean.contains(MARK_ALREADY_RUNNING)
                || clean.contains("准备监听:") || clean.contains("监听:"))
                && reason.isEmpty()) {
            steps.add(c.getString(R.string.pairing_diag_listening));
        }
        if (!steps.isEmpty()) {
            sb.append('\n').append(c.getString(R.string.pairing_diag_steps));
            for (String s : steps) {
                sb.append("\n  · ").append(s);
            }
        }
        return sb.toString();
    }
}
