package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.store.SkillApprovalStore;
import com.yangtze.bankwarning.ai.security.SkillMetadata;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Skill 审批服务（阶段三 · 产品化审批流）。
 *
 * 下载 skill 后自动生成待审批记录（PENDING），管理员通过管理接口批准或驳回，
 * 执行时 SkillGovernanceService 每次都查询数据库，批准后立即生效，无需重启。
 */
@Service
public class SkillApprovalService {

    private static final Logger log = LoggerFactory.getLogger(SkillApprovalService.class);

    private final SkillApprovalStore store;
    private final Path cacheDir;

    public SkillApprovalService(
            SkillApprovalStore store,
            @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}") String cacheDir) {
        this.store = store;
        this.cacheDir = Paths.get(cacheDir).toAbsolutePath().normalize();
    }

    /**
     * 下载 skill 后调用：按 SKILL.md 声明的权限生成待审批记录。
     * 幂等：同一 skill@version 的同一权限只生成一次，已审批/已驳回的记录不会被覆盖。
     *
     * @return 生成的待审批记录数
     */
    public int createPendingForSkill(String skillName, String version, Set<String> permissions, String requestedBy) {
        if (permissions == null || permissions.isEmpty()) {
            log.info("[skill-approval] skill {}@{} 未声明权限，无需审批", skillName, version);
            return 0;
        }
        for (String permission : permissions) {
            store.createPending(skillName, version, permission, requestedBy);
        }
        log.info("[skill-approval] skill {}@{} 生成 {} 条待审批记录，申请者={}",
                skillName, version, permissions.size(), requestedBy);
        return permissions.size();
    }

    public List<SkillApprovalStore.SkillApproval> listPending() {
        return store.listPending();
    }

    public List<SkillApprovalStore.SkillApproval> listByStatus(String status) {
        return store.listByStatus(status);
    }

    public List<SkillApprovalStore.SkillApproval> listBySkill(String skillName, String version) {
        return store.findApprovals(skillName, version);
    }

    /** 批准：更新状态并写审计；执行时自动生效，无需重启 */
    public boolean approve(Long id, String reviewer) {
        Optional<SkillApprovalStore.SkillApproval> row = store.findById(id);
        if (row.isEmpty() || !SkillApprovalStore.STATUS_PENDING.equals(row.get().status())) {
            return false;
        }
        SkillApprovalStore.SkillApproval approval = row.get();
        boolean ok = store.updateStatus(id, SkillApprovalStore.STATUS_APPROVED, reviewer, null);
        if (ok) {
            store.appendAudit(approval.skillName(), approval.version(), "APPROVED",
                    "permission=" + approval.permission() + " reviewer=" + reviewer, false);
        }
        return ok;
    }

    /** 驳回：更新状态并写审计 */
    public boolean reject(Long id, String reviewer, String comment) {
        Optional<SkillApprovalStore.SkillApproval> row = store.findById(id);
        if (row.isEmpty() || !SkillApprovalStore.STATUS_PENDING.equals(row.get().status())) {
            return false;
        }
        SkillApprovalStore.SkillApproval approval = row.get();
        boolean ok = store.updateStatus(id, SkillApprovalStore.STATUS_REJECTED, reviewer, comment);
        if (ok) {
            store.appendAudit(approval.skillName(), approval.version(), "REJECTED",
                    "permission=" + approval.permission() + " reviewer=" + reviewer + " comment=" + comment, true);
        }
        return ok;
    }

    /** 已驳回的记录重新提交审批：状态回到 PENDING，清空审批人与意见 */
    public boolean resubmit(Long id, String requester) {
        Optional<SkillApprovalStore.SkillApproval> row = store.findById(id);
        if (row.isEmpty() || !SkillApprovalStore.STATUS_REJECTED.equals(row.get().status())) {
            return false;
        }
        SkillApprovalStore.SkillApproval approval = row.get();
        boolean ok = store.updateStatus(id, SkillApprovalStore.STATUS_PENDING, null, null);
        if (ok) {
            store.appendAudit(approval.skillName(), approval.version(), "RESUBMITTED",
                    "permission=" + approval.permission() + " requester=" + requester, false);
        }
        return ok;
    }

    public List<SkillApprovalStore.AuditRecord> listAudit(int limit) {
        return store.listAudit(Math.max(1, Math.min(limit, 500)));
    }

    /** 聚合某 skill 的审批状态（版本 + 整体 + 逐权限），供 SKILLS 面板展示 */
    public Map<String, Object> listGovernanceStatus(String skillName) {
        try {
            SkillMetadata meta = resolveMetadata(skillName);
            String version = meta.getVersion();
            List<Map<String, String>> permissions = new ArrayList<>();
            String overall;
            if (meta.getPermissions().isEmpty()) {
                overall = "NONE";
            } else {
                Map<String, String> statusByPermission = new LinkedHashMap<>();
                for (SkillApprovalStore.SkillApproval approval : store.listByStatus(null)) {
                    if (approval.skillName().equals(skillName) && approval.version().equals(version)) {
                        statusByPermission.put(approval.permission(), approval.status());
                    }
                }
                boolean rejected = false;
                boolean pending = false;
                for (String permission : meta.getPermissions()) {
                    String status = statusByPermission.getOrDefault(permission, "NOT_REQUESTED");
                    permissions.add(Map.of("permission", permission, "status", status));
                    if ("REJECTED".equals(status)) {
                        rejected = true;
                    }
                    if ("PENDING".equals(status) || "NOT_REQUESTED".equals(status)) {
                        pending = true;
                    }
                }
                overall = rejected ? "REJECTED" : pending ? "PENDING" : "APPROVED";
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("version", version);
            result.put("approval", overall);
            result.put("permissions", permissions);
            return result;
        } catch (Exception e) {
            log.warn("[skill] 审批状态读取失败 {}: {}", skillName, e.getMessage());
            return Map.of("version", "0.0.0", "approval", "NONE", "permissions", List.of());
        }
    }

    /** 上传/发布后按 SKILL.md 声明的权限生成待审批记录（幂等） */
    public void createPendingFromContent(String skillName, String skillMdContent) {
        SkillMetadata metadata = SkillMetadata.parse(skillMdContent);
        createPendingForSkill(skillName, metadata.getVersion(), metadata.getPermissions(), requester());
    }

    /** 从缓存目录或 classpath 源目录读取 SKILL.md 解析元数据 */
    private SkillMetadata resolveMetadata(String skillName) {
        Path[] candidates = {
                cacheDir.resolve(skillName),
                Paths.get(System.getProperty("user.dir"), "src/main/resources/skills", skillName)
        };
        for (Path candidate : candidates) {
            SkillMetadata metadata = SkillMetadata.parse(candidate);
            if (!metadata.getName().isEmpty() || !SkillMetadata.DEFAULT_VERSION.equals(metadata.getVersion())) {
                return metadata;
            }
        }
        return SkillMetadata.empty();
    }

    private static String requester() {
        String name = SecurityUtils.getCurrentUsername();
        return name == null ? "system" : name;
    }
}
