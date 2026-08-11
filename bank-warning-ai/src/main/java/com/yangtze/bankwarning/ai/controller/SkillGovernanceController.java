package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.security.SkillApprovalService;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Skill 治理管理接口（阶段三 · 产品化审批流）。
 *
 * 全部要求 ADMIN 角色，供管理端页面/接口调用：
 *   - 查看待审批列表、某 skill 的审批记录；
 *   - 批准 / 驳回（带操作人和意见）；
 *   - 查看审计日志。
 */
@RestController
@RequestMapping("/v0/admin/skill-governance")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class SkillGovernanceController {

    private final SkillApprovalService approvalService;

    public SkillGovernanceController(SkillApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/approvals")
    public Map<String, Object> listApprovals(
            @RequestParam(defaultValue = "PENDING") String status) {
        String queryStatus = "ALL".equalsIgnoreCase(status) ? null : status;
        return Map.of("success", true, "approvals", approvalService.listByStatus(queryStatus));
    }

    @GetMapping("/approvals/skill/{skillName}")
    public Map<String, Object> listBySkill(
            @PathVariable String skillName,
            @RequestParam(defaultValue = "0.0.0") String version) {
        return Map.of("success", true, "approvals", approvalService.listBySkill(skillName, version));
    }

    @PostMapping("/approvals/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) {
        boolean ok = approvalService.approve(id, reviewer());
        return ok ? Map.of("success", true)
                : Map.of("success", false, "error", "审批记录不存在或已处理");
    }

    @PostMapping("/approvals/{id}/reject")
    public Map<String, Object> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String comment = body == null ? null : body.get("comment");
        boolean ok = approvalService.reject(id, reviewer(), comment);
        return ok ? Map.of("success", true)
                : Map.of("success", false, "error", "审批记录不存在或已处理");
    }

    @PostMapping("/approvals/{id}/resubmit")
    public Map<String, Object> resubmit(@PathVariable Long id) {
        boolean ok = approvalService.resubmit(id, reviewer());
        return ok ? Map.of("success", true)
                : Map.of("success", false, "error", "仅已驳回的记录可重新提交");
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "100") int limit) {
        return Map.of("success", true, "audit", approvalService.listAudit(limit));
    }

    private static String reviewer() {
        String name = SecurityUtils.getCurrentUsername();
        return name == null ? "system" : name;
    }
}
