package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.SkillSecurityProfile;
import com.yangtze.bankwarning.ai.security.SkillSecuritySettings;
import com.yangtze.bankwarning.ai.security.SkillSecuritySettingsProvider;
import com.yangtze.bankwarning.ai.store.SkillApprovalStore;
import com.yangtze.bankwarning.ai.store.SkillSecurityProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 安全档位服务（阶段一/二/三的统一策略源）。
 *
 * <p>三层防护里的每个组件都改为在<b>每次判断时</b>读 {@link #settings()}，
 * 而不是把参数固化在构造期。因此「切档位」是一次纯内存替换 + 一次落库，
 * 立即生效、不需要重启 —— 熔断（kill switch）终于名副其实。
 *
 * <p>两条刻意的设计约束：
 * <ol>
 *   <li><b>切档位不动运维状态。</b> {@code killSwitch} / {@code allowedVersions} /
 *       {@code quarantinedVersions} 是"当前被封的是谁"这类运维数据，不是策略预设值。
 *       套用新档位时会原样保留，避免"换个档位不小心把熔断解除了"。</li>
 *   <li><b>读失败不阻断业务。</b> 数据库不可用时回退到内存中的默认档位（STANDARD），
 *       而不是让整个 skill 链路抛异常。</li>
 * </ol>
 */
@Service
public class SkillSecurityService implements SkillSecuritySettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(SkillSecurityService.class);

    /** 档位变更事件在审计表里挂在这个伪 skill 名下（该表 skill_name 为 NOT NULL） */
    public static final String AUDIT_SKILL_NAME = "__security_profile__";

    public static final String EVENT_INITIALIZED = "PROFILE_INITIALIZED";
    public static final String EVENT_CHANGED = "PROFILE_CHANGED";
    public static final String EVENT_TUNED = "PROFILE_TUNED";
    public static final String EVENT_KILL_SWITCH = "KILL_SWITCH";

    private final SkillSecurityProfileStore store;
    private final SkillApprovalStore auditStore;
    private final AtomicReference<SkillSecuritySettings> current =
            new AtomicReference<>(SkillSecuritySettings.preset(SkillSecurityProfile.STANDARD));
    private volatile boolean loaded = false;

    public SkillSecurityService(SkillSecurityProfileStore store, SkillApprovalStore auditStore) {
        this.store = store;
        this.auditStore = auditStore;
    }

    /** 当前生效档位（每次判断都读它） */
    public SkillSecuritySettings settings() {
        ensureLoaded();
        return current.get();
    }

    /**
     * 套用某个预设档位。运维状态（熔断 / 白名单 / 隔离清单）原样保留。
     */
    public synchronized SkillSecuritySettings applyProfile(SkillSecurityProfile next, String actor) {
        ensureLoaded();
        SkillSecuritySettings before = current.get();
        SkillSecuritySettings applied = SkillSecuritySettings.preset(next);
        SkillSecuritySettings merged = new SkillSecuritySettings(
                next,
                applied.verifyEnabled(),
                applied.failOnViolation(),
                applied.forbiddenImports(),
                applied.requireChecksumManifest(),
                applied.requireSignedManifest(),
                applied.sandboxMode(),
                applied.sandboxTimeoutSeconds(),
                applied.sandboxMaxOutputBytes(),
                applied.sandboxMemoryMb(),
                applied.sandboxCpuSeconds(),
                applied.sandboxPidsLimit(),
                applied.governanceEnabled(),
                // 白名单与隔离清单是运维状态，换档位时不动
                before.allowedVersions(),
                before.quarantinedVersions(),
                applied.failOnUnapprovedPermission(),
                applied.enforceOutputContract(),
                // 熔断同理：换档位不能顺手解除熔断
                before.killSwitch(),
                actor,
                null);
        persist(merged, actor);
        audit(EVENT_CHANGED, "profile " + before.profile() + " -> " + next + " by " + actor
                + "；运维状态保留：killSwitch=" + merged.killSwitch()
                + " allowedVersions=" + merged.allowedVersions()
                + " quarantined=" + merged.quarantinedVersions(), false);
        log.warn("[skill-security] 档位切换 {} -> {} by {}", before.profile(), next, actor);
        return merged;
    }

    /**
     * 逐项微调（应急用）。只覆盖显式传入的字段，其余保持当前值。
     */
    public synchronized SkillSecuritySettings patch(ProfilePatch patch, String actor) {
        ensureLoaded();
        SkillSecuritySettings s = current.get();
        SkillSecuritySettings merged = new SkillSecuritySettings(
                s.profile(),
                patch.verifyEnabled() == null ? s.verifyEnabled() : patch.verifyEnabled(),
                patch.failOnViolation() == null ? s.failOnViolation() : patch.failOnViolation(),
                patch.forbiddenImports() == null ? s.forbiddenImports() : patch.forbiddenImports(),
                patch.requireChecksumManifest() == null ? s.requireChecksumManifest() : patch.requireChecksumManifest(),
                patch.requireSignedManifest() == null ? s.requireSignedManifest() : patch.requireSignedManifest(),
                patch.sandboxMode() == null ? s.sandboxMode() : patch.sandboxMode(),
                patch.sandboxTimeoutSeconds() == null ? s.sandboxTimeoutSeconds() : patch.sandboxTimeoutSeconds(),
                patch.sandboxMaxOutputBytes() == null ? s.sandboxMaxOutputBytes() : patch.sandboxMaxOutputBytes(),
                patch.sandboxMemoryMb() == null ? s.sandboxMemoryMb() : patch.sandboxMemoryMb(),
                patch.sandboxCpuSeconds() == null ? s.sandboxCpuSeconds() : patch.sandboxCpuSeconds(),
                patch.sandboxPidsLimit() == null ? s.sandboxPidsLimit() : patch.sandboxPidsLimit(),
                patch.governanceEnabled() == null ? s.governanceEnabled() : patch.governanceEnabled(),
                patch.allowedVersions() == null ? s.allowedVersions() : patch.allowedVersions(),
                patch.quarantinedVersions() == null ? s.quarantinedVersions() : patch.quarantinedVersions(),
                patch.failOnUnapprovedPermission() == null ? s.failOnUnapprovedPermission() : patch.failOnUnapprovedPermission(),
                patch.enforceOutputContract() == null ? s.enforceOutputContract() : patch.enforceOutputContract(),
                s.killSwitch(),
                actor,
                null);
        validate(merged);
        persist(merged, actor);
        boolean drifted = !merged.matchesPreset();
        audit(EVENT_TUNED, "逐项微调 by " + actor
                + "；当前是否偏离预设=" + drifted
                + " forbidden=" + merged.forbiddenImports().size() + "项"
                + " sandbox=" + merged.normalizedMode() + "/" + merged.sandboxTimeoutSeconds() + "s"
                + "/" + merged.sandboxMaxOutputBytes() + "B"
                + " allowedVersions=" + merged.allowedVersions(), drifted);
        log.warn("[skill-security] 档位逐项微调 by {}，偏离预设={}", actor, drifted);
        return merged;
    }

    /**
     * 应急熔断：立即生效，无需重启。这是"出事时第一下按的按钮"。
     */
    public synchronized SkillSecuritySettings setKillSwitch(boolean enabled, String actor) {
        ensureLoaded();
        SkillSecuritySettings s = current.get();
        SkillSecuritySettings merged = new SkillSecuritySettings(
                s.profile(), s.verifyEnabled(), s.failOnViolation(), s.forbiddenImports(),
                s.requireChecksumManifest(), s.requireSignedManifest(), s.sandboxMode(),
                s.sandboxTimeoutSeconds(), s.sandboxMaxOutputBytes(), s.sandboxMemoryMb(),
                s.sandboxCpuSeconds(), s.sandboxPidsLimit(), s.governanceEnabled(),
                s.allowedVersions(), s.quarantinedVersions(), s.failOnUnapprovedPermission(),
                s.enforceOutputContract(), enabled, actor, null);
        persist(merged, actor);
        audit(EVENT_KILL_SWITCH, (enabled ? "开启" : "解除") + "全局熔断 by " + actor, enabled);
        log.warn("[skill-security] 全局熔断 {} by {}", enabled ? "开启" : "解除", actor);
        return merged;
    }

    /**
     * 档位变更历史（复用审计表，只看 {@code PROFILE_*} / {@code KILL_SWITCH} 事件）。
     *
     * <p>审计表里混着 skill 执行事件，而档位事件是低频的，
     * 因此按 <b>请求条数放大 5 倍</b> 去取审计行（下限 200），保证筛出来的档位事件够数，
     * 而不是"翻了 50 条审计、里面只有 3 条是档位事件"。
     */
    public List<SkillApprovalStore.AuditRecord> history(int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        int scanDepth = Math.min(Math.max(200, capped * 5), 2000);
        return auditStore.listAudit(scanDepth).stream()
                .filter(r -> AUDIT_SKILL_NAME.equals(r.skillName()))
                .filter(r -> r.eventType() != null && (r.eventType().startsWith("PROFILE")
                        || EVENT_KILL_SWITCH.equals(r.eventType())))
                .limit(capped)
                .toList();
    }

    /** 首次访问时从数据库装载；库里没有则用 STANDARD 预设播种（等于改造前的既有行为） */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                SkillSecuritySettings seeded = store.load().orElse(null);
                if (seeded == null) {
                    // 落库的初始值 = STANDARD 预设，与 yml 既有默认值逐项一致，保证行为不变
                    SkillSecuritySettings initial = SkillSecuritySettings.preset(SkillSecurityProfile.STANDARD);
                    store.save(initial, "system");
                    current.set(initial);
                    audit(EVENT_INITIALIZED, "首次落库，按 STANDARD 预设播种（等价于改造前的既有默认值）", false);
                    log.info("[skill-security] 档位首次落库：STANDARD");
                } else {
                    current.set(seeded);
                    log.info("[skill-security] 装载档位 {}（偏离预设={}）",
                            seeded.profile(), !seeded.matchesPreset());
                }
                loaded = true;
            } catch (Exception e) {
                // 数据库不可用不阻断业务：内存里保持 STANDARD 预设
                log.warn("[skill-security] 读取档位失败，暂用内存默认档位 STANDARD: {}", e.getMessage());
                loaded = true;
            }
        }
    }

    private void persist(SkillSecuritySettings settings, String actor) {
        current.set(new SkillSecuritySettings(
                settings.profile(), settings.verifyEnabled(), settings.failOnViolation(),
                settings.forbiddenImports(), settings.requireChecksumManifest(),
                settings.requireSignedManifest(), settings.sandboxMode(),
                settings.sandboxTimeoutSeconds(), settings.sandboxMaxOutputBytes(),
                settings.sandboxMemoryMb(), settings.sandboxCpuSeconds(), settings.sandboxPidsLimit(),
                settings.governanceEnabled(), settings.allowedVersions(), settings.quarantinedVersions(),
                settings.failOnUnapprovedPermission(), settings.enforceOutputContract(),
                settings.killSwitch(), actor, null));
        try {
            store.save(settings, actor);
        } catch (Exception e) {
            // 落库失败：内存已生效（本次请求立即按新档位执行），但要显式告警，避免悄悄丢策略
            log.error("[skill-security] 档位落库失败，仅内存生效（重启会回到库里的值）: {}", e.getMessage());
        }
    }

    private void audit(String eventType, String detail, boolean blocked) {
        try {
            auditStore.appendAudit(AUDIT_SKILL_NAME, current.get().profile().name(), eventType, detail, blocked);
        } catch (Exception e) {
            log.warn("[skill-security] 审计写入失败: {}", e.getMessage());
        }
    }

    /** 参数合理性校验：拒绝明显会打爆业务的组合，而不是默默接受 */
    private static void validate(SkillSecuritySettings s) {
        require(s.sandboxTimeoutSeconds() >= 5 && s.sandboxTimeoutSeconds() <= 3600,
                "sandboxTimeoutSeconds 必须在 5~3600 秒之间");
        require(s.sandboxMaxOutputBytes() >= 1024 && s.sandboxMaxOutputBytes() <= 64 * 1024 * 1024,
                "sandboxMaxOutputBytes 必须在 1KB~64MB 之间");
        require(s.sandboxMemoryMb() >= 64 && s.sandboxMemoryMb() <= 8192,
                "sandboxMemoryMb 必须在 64~8192 之间");
        require(s.sandboxCpuSeconds() >= 1 && s.sandboxCpuSeconds() <= 600,
                "sandboxCpuSeconds 必须在 1~600 之间");
        require(s.sandboxPidsLimit() >= 8 && s.sandboxPidsLimit() <= 4096,
                "sandboxPidsLimit 必须在 8~4096 之间");
        if (s.requireSignedManifest() && !s.requireChecksumManifest()) {
            require(false, "要求签名必须先要求校验清单存在（requireChecksumManifest 不能为 false）");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 逐项微调的入参：null 表示"这一项不改" */
    public record ProfilePatch(
            Boolean verifyEnabled,
            Boolean failOnViolation,
            List<String> forbiddenImports,
            Boolean requireChecksumManifest,
            Boolean requireSignedManifest,
            String sandboxMode,
            Integer sandboxTimeoutSeconds,
            Integer sandboxMaxOutputBytes,
            Integer sandboxMemoryMb,
            Integer sandboxCpuSeconds,
            Integer sandboxPidsLimit,
            Boolean governanceEnabled,
            List<String> allowedVersions,
            List<String> quarantinedVersions,
            Boolean failOnUnapprovedPermission,
            Boolean enforceOutputContract) {
    }
}
