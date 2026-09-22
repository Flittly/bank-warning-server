package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.SkillSecuritySettings;
import com.yangtze.bankwarning.ai.security.SkillSecuritySettingsProvider;
import com.yangtze.bankwarning.ai.store.SkillApprovalStore;
import com.yangtze.bankwarning.ai.store.SkillVersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 治理服务（阶段三 · 治理闭环）。
 *
 * 执行前裁决：全局熔断、版本批准清单、隔离清单、权限审批；
 * 执行后留痕：审计事件写入 SkillApprovalStore。
 * 任一检查不通过则拒绝执行（是否 fail-closed 由当前档位决定，见 SkillSecurityService）。
 *
 * <p>本类不再持有任何 {@code final} 的 {@code @Value} 开关：每次 {@link #evaluate}
 * 都从 {@link SkillSecurityService#settings()} 读当前档位，
 * 因此管理员切档位或按下熔断后，<b>下一次执行立即生效，无需重启</b>。
 */
@Service
public class SkillGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(SkillGovernanceService.class);

    private final SkillApprovalStore store;
    private final SkillVersionStore versionStore;
    private final SkillSecuritySettingsProvider settingsProvider;

    public SkillGovernanceService(SkillApprovalStore store,
                                  SkillVersionStore versionStore,
                                  SkillSecuritySettingsProvider settingsProvider) {
        this.store = store;
        this.versionStore = versionStore;
        this.settingsProvider = settingsProvider;
    }

    /**
     * 执行前裁决。
     *
     * @param skillName            skill 名
     * @param version              skill 版本（SKILL.md frontmatter，缺失为 0.0.0）
     * @param requestedPermissions SKILL.md 声明的权限，可为空
     * @return 裁决结果（是否放行 + 拦截原因 + 警告）
     */
    public GovernanceDecision evaluate(String skillName, String version, Set<String> requestedPermissions) {
        SkillSecuritySettings settings = settingsProvider.settings();
        if (!settings.governanceEnabled()) {
            return GovernanceDecision.allow();
        }
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String key = skillName + "@" + version;

        if (settings.killSwitch()) {
            reasons.add("全局熔断（kill switch）已开启");
        }
        if (settings.quarantinedVersions().contains(key)) {
            reasons.add("该版本已被隔离: " + key);
        }
        // skill_versions 表里的版本状态也参与裁决（QUARANTINED/RETIRED 一律拒绝）
        if (versionStore != null) {
            versionStore.findByVersion(skillName, version)
                    .filter(v -> !SkillVersionStore.STATUS_ACTIVE.equals(v.status()))
                    .ifPresent(v -> reasons.add("版本状态不允许执行: " + key + " (" + v.status() + ")"));
        }
        if (!settings.allowedVersions().isEmpty() && !settings.allowedVersions().contains(key)) {
            reasons.add("版本不在批准清单内: " + key);
        }

        Set<String> approved = store.findApprovedPermissions(skillName, version).stream()
                .map(SkillApprovalStore.SkillApproval::permission)
                .collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>();
        for (String permission : requestedPermissions == null ? Set.<String>of() : requestedPermissions) {
            if (!approved.contains(permission)) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            String detail = "权限未审批: " + String.join(", ", missing);
            if (settings.failOnUnapprovedPermission()) {
                reasons.add(detail);
            } else {
                warnings.add(detail);
            }
        }
        return new GovernanceDecision(reasons.isEmpty(), reasons, warnings);
    }

    /** 追加审计事件（写入失败只告警，不阻断业务） */
    public void recordAudit(String skillName, String version, String eventType, String detail, boolean blocked) {
        try {
            store.appendAudit(skillName, version, eventType, detail, blocked);
        } catch (Exception e) {
            log.warn("[skill-governance] 审计写入失败: {}", e.getMessage());
        }
    }

    /** 治理裁决结果 */
    public record GovernanceDecision(boolean allowed, List<String> reasons, List<String> warnings) {

        public static GovernanceDecision allow() {
            return new GovernanceDecision(true, List.of(), List.of());
        }

        public boolean isAllowed() {
            return allowed;
        }
    }
}
