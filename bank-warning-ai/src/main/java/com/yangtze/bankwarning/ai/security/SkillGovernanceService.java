package com.yangtze.bankwarning.ai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 治理服务（阶段三 · 治理闭环）。
 *
 * 执行前裁决：全局熔断、版本批准清单、隔离清单、权限审批；
 * 执行后留痕：审计事件写入 SkillApprovalStore。
 * 任一检查不通过且对应开关为严格模式时，拒绝执行（fail-closed）。
 */
@Service
public class SkillGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(SkillGovernanceService.class);

    private final SkillApprovalStore store;
    private final boolean enabled;
    private final boolean killSwitch;
    private final Set<String> allowedVersions;
    private final Set<String> quarantinedVersions;
    private final boolean failOnUnapprovedPermission;

    public SkillGovernanceService(
            SkillApprovalStore store,
            @Value("${app.ai.skill.governance.enabled:true}") boolean enabled,
            @Value("${app.ai.skill.governance.kill-switch:false}") boolean killSwitch,
            @Value("${app.ai.skill.governance.allowed-versions:}") List<String> allowedVersions,
            @Value("${app.ai.skill.governance.quarantined-versions:}") List<String> quarantinedVersions,
            @Value("${app.ai.skill.governance.fail-on-unapproved-permission:true}") boolean failOnUnapprovedPermission) {
        this.store = store;
        this.enabled = enabled;
        this.killSwitch = killSwitch;
        this.allowedVersions = normalize(allowedVersions);
        this.quarantinedVersions = normalize(quarantinedVersions);
        this.failOnUnapprovedPermission = failOnUnapprovedPermission;
        log.info("[skill-governance] enabled={} killSwitch={} allowedVersions={} quarantined={}",
                enabled, killSwitch, this.allowedVersions, this.quarantinedVersions);
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
        if (!enabled) {
            return GovernanceDecision.allow();
        }
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String key = skillName + "@" + version;

        if (killSwitch) {
            reasons.add("全局熔断（kill switch）已开启");
        }
        if (quarantinedVersions.contains(key)) {
            reasons.add("该版本已被隔离: " + key);
        }
        if (!allowedVersions.isEmpty() && !allowedVersions.contains(key)) {
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
            if (failOnUnapprovedPermission) {
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

    private static Set<String> normalize(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String entry : entries) {
            if (entry != null && !entry.isBlank()) {
                result.add(entry.trim());
            }
        }
        return result;
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
