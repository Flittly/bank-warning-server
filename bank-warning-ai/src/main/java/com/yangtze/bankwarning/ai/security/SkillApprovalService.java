package com.yangtze.bankwarning.ai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public SkillApprovalService(SkillApprovalStore store) {
        this.store = store;
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

    public List<SkillApprovalStore.AuditRecord> listAudit(int limit) {
        return store.listAudit(Math.max(1, Math.min(limit, 500)));
    }
}
