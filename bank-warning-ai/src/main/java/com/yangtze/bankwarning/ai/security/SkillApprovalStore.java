package com.yangtze.bankwarning.ai.security;

import java.util.List;
import java.util.Optional;

/**
 * Skill 治理数据存储（阶段三 · 审批与审计）。
 *
 * 职责：
 *   - 读取某 skill 某版本已审批通过的权限清单；
 *   - 生成/查询待审批记录，批准或驳回（产品化审批流）；
 *   - 追加审计事件（发布/校验/执行/拦截等，留痕可追溯）。
 * 默认实现是 JdbcSkillApprovalStore（PostgreSQL），测试可换内存实现。
 */
public interface SkillApprovalStore {

    String STATUS_PENDING = "PENDING";
    String STATUS_APPROVED = "APPROVED";
    String STATUS_REJECTED = "REJECTED";

    /** 查询某 skill 某版本已审批通过的权限 */
    List<SkillApproval> findApprovedPermissions(String skillName, String version);

    /** 查询某 skill 某版本的全部审批记录（含待审批/已驳回） */
    List<SkillApproval> findApprovals(String skillName, String version);

    /** 查询全部待审批记录 */
    List<SkillApproval> listPending();

    /** 按状态查询审批记录，status 为 null 时返回全部 */
    List<SkillApproval> listByStatus(String status);

    /** 按 id 查询一条审批记录 */
    Optional<SkillApproval> findById(Long id);

    /** 幂等生成一条待审批记录（已存在则不动） */
    void createPending(String skillName, String version, String permission, String requestedBy);

    /** 更新审批状态（APPROVED/REJECTED），记录审批人与时间，返回是否更新成功 */
    boolean updateStatus(Long id, String status, String reviewer, String comment);

    /** 最近 N 条审计记录 */
    List<AuditRecord> listAudit(int limit);

    /** 追加一条审计事件 */
    void appendAudit(String skillName, String version, String eventType, String detail, boolean blocked);

    /** 一条审批记录 */
    record SkillApproval(Long id, String skillName, String version, String permission,
                         String status, String requestedBy, String reviewedBy, String comment) {
    }

    /** 一条审计记录 */
    record AuditRecord(String skillName, String version, String eventType, String detail,
                       boolean blocked, String createdAt) {
    }
}
