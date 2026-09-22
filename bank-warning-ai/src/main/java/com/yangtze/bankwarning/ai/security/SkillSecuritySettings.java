package com.yangtze.bankwarning.ai.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Skill 安全档位的<b>生效值</b>快照（不可变）。
 *
 * <p>这是三层防护的唯一真相源：阶段一（校验/扫描）、阶段二（沙箱）、阶段三（治理/输出契约）
 * 全部从这里读参数，不再各自持有 {@code final} 的 {@code @Value} 字段。
 * 好处是「档位」与「逐项微调」都只需替换一个快照对象，
 * 判断逻辑本身（那些 {@code if (killSwitch)}）一行都不用改。
 *
 * <p>{@link #preset(SkillSecurityProfile)} 提供三档预设；
 * {@code STANDARD} 的各项取值刻意等于系统改造前的既有默认值，
 * 保证「首次落库 = 行为不变」。
 */
public record SkillSecuritySettings(

        /** 所属档位（LOOSE / STANDARD / STRICT）；逐项微调后此标签不变，仅用于回显与「是否偏离预设」判断 */
        SkillSecurityProfile profile,

        /* ---------- 阶段一 · 校验与静态扫描 ---------- */
        /** 静态扫描总开关；关闭时只剩路径防逃逸（对齐原 yml 注释的语义，此前该开关无任何代码读取） */
        boolean verifyEnabled,
        /** 扫描到危险 import 时：true=拒绝执行，false=仅告警并写审计 */
        boolean failOnViolation,
        /** 危险模块黑名单 */
        List<String> forbiddenImports,
        /** 下载的 zip 缺少 .bank-checksum.sha256 清单时是否拒绝 */
        boolean requireChecksumManifest,
        /** 是否强制清单必须带 HMAC 签名（无密钥时无法强制，见 SkillContentVerifier） */
        boolean requireSignedManifest,

        /* ---------- 阶段二 · 执行隔离 ---------- */
        /** OFF / PROCESS / DOCKER */
        String sandboxMode,
        int sandboxTimeoutSeconds,
        int sandboxMaxOutputBytes,
        int sandboxMemoryMb,
        int sandboxCpuSeconds,
        int sandboxPidsLimit,

        /* ---------- 阶段三 · 治理闭环 ---------- */
        boolean governanceEnabled,
        /** 非空即进入白名单模式：清单外的 skill@version 一律拒绝执行 */
        List<String> allowedVersions,
        List<String> quarantinedVersions,
        /** SKILL.md 声明的权限未审批时：true=拒绝执行，false=仅告警 */
        boolean failOnUnapprovedPermission,
        /** 输出契约校验失败时：true=拒绝并丢弃输出，false=仅告警后透传 */
        boolean enforceOutputContract,

        /* ---------- 应急开关（不属于任何预设，只能显式操作） ---------- */
        boolean killSwitch,

        /* ---------- 审计元信息 ---------- */
        String updatedBy,
        String updatedAt) {

    /** 严格档在默认黑名单之外追加的模块 */
    public static final List<String> STRICT_EXTRA_FORBIDDEN = List.of(
            // 反序列化执行
            "pickle", "marshal", "shelve",
            // 动态导入，用于绕过静态扫描
            "importlib", "runpy",
            // 直接开终端 / 浏览器 / 直连网络
            "pty", "webbrowser", "requests"
    );

    private static final Set<String> VALID_SANDBOX_MODES = Set.of("OFF", "PROCESS", "DOCKER");

    /**
     * 三档预设。
     *
     * <p>刻意<b>不</b>在预设里启用 {@code allowed-versions} 白名单：
     * 一旦启用，所有未登记的版本会立刻被拒绝执行（等于把 pdf/word 一起锁死）。
     * 它是"应急收紧"能力而不是"档位"，因此只由管理员显式开启。
     */
    public static SkillSecuritySettings preset(SkillSecurityProfile profile) {
        List<String> base = PythonImportScanner.DEFAULT_FORBIDDEN;
        return switch (profile) {
            case LOOSE -> new SkillSecuritySettings(
                    profile,
                    true, false, base, false, false,
                    "OFF", 120, 1_048_576, 512, 30, 64,
                    true, List.of(), List.of(), false, false,
                    false, null, null);
            case STANDARD -> new SkillSecuritySettings(
                    profile,
                    true, true, base, true, false,
                    "PROCESS", 120, 1_048_576, 512, 30, 64,
                    true, List.of(), List.of(), true, true,
                    false, null, null);
            case STRICT -> new SkillSecuritySettings(
                    profile,
                    true, true, concat(base, STRICT_EXTRA_FORBIDDEN), true, true,
                    "PROCESS", 30, 262_144, 512, 15, 32,
                    true, List.of(), List.of(), true, true,
                    false, null, null);
        };
    }

    /** 当前值与所属预设是否有差异（被逐项微调过） */
    public boolean matchesPreset() {
        SkillSecuritySettings reference = preset(profile);
        return verifyEnabled == reference.verifyEnabled()
                && failOnViolation == reference.failOnViolation()
                && sameSet(forbiddenImports, reference.forbiddenImports())
                && requireChecksumManifest == reference.requireChecksumManifest()
                && requireSignedManifest == reference.requireSignedManifest()
                && normalizedMode().equals(reference.sandboxMode())
                && sandboxTimeoutSeconds == reference.sandboxTimeoutSeconds()
                && sandboxMaxOutputBytes == reference.sandboxMaxOutputBytes()
                && sandboxMemoryMb == reference.sandboxMemoryMb()
                && sandboxCpuSeconds == reference.sandboxCpuSeconds()
                && sandboxPidsLimit == reference.sandboxPidsLimit()
                && governanceEnabled == reference.governanceEnabled()
                && sameSet(allowedVersions, reference.allowedVersions())
                && sameSet(quarantinedVersions, reference.quarantinedVersions())
                && failOnUnapprovedPermission == reference.failOnUnapprovedPermission()
                && enforceOutputContract == reference.enforceOutputContract();
    }

    /** 规范化沙箱档位字符串（非法值回退 PROCESS，不抛异常） */
    public String normalizedMode() {
        if (sandboxMode == null || sandboxMode.isBlank()) {
            return "PROCESS";
        }
        String upper = sandboxMode.trim().toUpperCase(Locale.ROOT);
        return VALID_SANDBOX_MODES.contains(upper) ? upper : "PROCESS";
    }

    /** 是否处于应急熔断 */
    public boolean halted() {
        return killSwitch;
    }

    /** 复制并替换档位标签（其余项不变） */
    public SkillSecuritySettings withProfile(SkillSecurityProfile next) {
        return new SkillSecuritySettings(next, verifyEnabled, failOnViolation, forbiddenImports,
                requireChecksumManifest, requireSignedManifest, sandboxMode, sandboxTimeoutSeconds,
                sandboxMaxOutputBytes, sandboxMemoryMb, sandboxCpuSeconds, sandboxPidsLimit,
                governanceEnabled, allowedVersions, quarantinedVersions, failOnUnapprovedPermission,
                enforceOutputContract, killSwitch, updatedBy, updatedAt);
    }

    /**
     * 以某预设为底，覆盖阶段一的扫描策略（黑名单 + 发现即拒绝）。
     * 供单元测试与「需要固定参数」的场景使用，避免在调用处手写 20 个字段。
     */
    public static SkillSecuritySettings withScanPolicy(SkillSecurityProfile base,
                                                       List<String> forbiddenImports,
                                                       boolean failOnViolation) {
        SkillSecuritySettings s = preset(base);
        return new SkillSecuritySettings(s.profile(), s.verifyEnabled(), failOnViolation, forbiddenImports,
                s.requireChecksumManifest(), s.requireSignedManifest(), s.sandboxMode(), s.sandboxTimeoutSeconds(),
                s.sandboxMaxOutputBytes(), s.sandboxMemoryMb(), s.sandboxCpuSeconds(), s.sandboxPidsLimit(),
                s.governanceEnabled(), s.allowedVersions(), s.quarantinedVersions(),
                s.failOnUnapprovedPermission(), s.enforceOutputContract(), s.killSwitch(), null, null);
    }

    /** 以某预设为底，覆盖沙箱档位与超时/输出上限 */
    public static SkillSecuritySettings withSandboxPolicy(SkillSecurityProfile base, String mode,
                                                          int timeoutSeconds, int maxOutputBytes) {
        SkillSecuritySettings s = preset(base);
        return new SkillSecuritySettings(s.profile(), s.verifyEnabled(), s.failOnViolation(), s.forbiddenImports(),
                s.requireChecksumManifest(), s.requireSignedManifest(), mode, timeoutSeconds, maxOutputBytes,
                s.sandboxMemoryMb(), s.sandboxCpuSeconds(), s.sandboxPidsLimit(),
                s.governanceEnabled(), s.allowedVersions(), s.quarantinedVersions(),
                s.failOnUnapprovedPermission(), s.enforceOutputContract(), s.killSwitch(), null, null);
    }

    /** 以某预设为底，覆盖治理裁决的开关 */
    public static SkillSecuritySettings withGovernancePolicy(SkillSecurityProfile base, boolean enabled,
                                                             boolean killSwitch,
                                                             List<String> allowedVersions,
                                                             List<String> quarantinedVersions,
                                                             boolean failOnUnapprovedPermission) {
        SkillSecuritySettings s = preset(base);
        return new SkillSecuritySettings(s.profile(), s.verifyEnabled(), s.failOnViolation(), s.forbiddenImports(),
                s.requireChecksumManifest(), s.requireSignedManifest(), s.sandboxMode(), s.sandboxTimeoutSeconds(),
                s.sandboxMaxOutputBytes(), s.sandboxMemoryMb(), s.sandboxCpuSeconds(), s.sandboxPidsLimit(),
                enabled,
                allowedVersions == null ? List.of() : allowedVersions,
                quarantinedVersions == null ? List.of() : quarantinedVersions,
                failOnUnapprovedPermission, s.enforceOutputContract(), killSwitch, null, null);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> merged = new ArrayList<>(a);
        merged.addAll(b);
        return List.copyOf(merged);
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        if (a == null || b == null) {
            return (a == null ? List.of() : a).isEmpty() && (b == null ? List.of() : b).isEmpty();
        }
        return new LinkedHashSet<>(a).equals(new LinkedHashSet<>(b));
    }
}
