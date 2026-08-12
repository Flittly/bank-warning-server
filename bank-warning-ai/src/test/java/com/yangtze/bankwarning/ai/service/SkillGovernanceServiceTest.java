package com.yangtze.bankwarning.ai.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yangtze.bankwarning.ai.security.SkillApprovalStore;
import com.yangtze.bankwarning.ai.security.SkillApprovalStore.SkillApproval;

class SkillGovernanceServiceTest {

    private static final class MemoryStore implements SkillApprovalStore {
        final List<SkillApproval> approvals = new ArrayList<>();
        final List<String> audits = new ArrayList<>();

        @Override
        public List<SkillApproval> findApprovedPermissions(String skillName, String version) {
            return approvals.stream()
                    .filter(a -> a.skillName().equals(skillName) && a.version().equals(version)
                            && STATUS_APPROVED.equals(a.status()))
                    .toList();
        }

        @Override
        public void appendAudit(String skillName, String version, String eventType, String detail, boolean blocked) {
            audits.add(skillName + "@" + version + " " + eventType + " blocked=" + blocked);
        }

        @Override
        public List<SkillApproval> findApprovals(String skillName, String version) {
            return approvals.stream()
                    .filter(a -> a.skillName().equals(skillName) && a.version().equals(version))
                    .toList();
        }

        @Override
        public List<SkillApproval> listPending() {
            return approvals.stream().filter(a -> STATUS_PENDING.equals(a.status())).toList();
        }

        @Override
        public List<SkillApproval> listByStatus(String status) {
            if (status == null) {
                return List.copyOf(approvals);
            }
            return approvals.stream().filter(a -> status.equals(a.status())).toList();
        }

        @Override
        public java.util.Optional<SkillApproval> findById(Long id) {
            return approvals.stream().filter(a -> id.equals(a.id())).findFirst();
        }

        @Override
        public void createPending(String skillName, String version, String permission, String requestedBy) {
            approvals.add(new SkillApproval(
                    (long) approvals.size() + 1, skillName, version, permission,
                    STATUS_PENDING, requestedBy, null, null));
        }

        @Override
        public boolean updateStatus(Long id, String status, String reviewer, String comment) {
            java.util.Optional<SkillApproval> row = findById(id);
            if (row.isEmpty()) {
                return false;
            }
            approvals.remove(row.get());
            SkillApproval a = row.get();
            approvals.add(new SkillApproval(
                    a.id(), a.skillName(), a.version(), a.permission(),
                    status, a.requestedBy(), reviewer, comment));
            return true;
        }

        @Override
        public List<AuditRecord> listAudit(int limit) {
            return List.of();
        }
    }

    private static SkillGovernanceService service(
            MemoryStore store, boolean enabled, boolean killSwitch,
            List<String> allowed, List<String> quarantined, boolean failOnUnapproved) {
        return new SkillGovernanceService(
                store, enabled, killSwitch, allowed, quarantined, failOnUnapproved);
    }

    @Test
    void disabledGovernanceAllowsEverything() {
        MemoryStore store = new MemoryStore();
        SkillGovernanceService s = service(store, false, true, List.of(), List.of(), true);
        assertTrue(s.evaluate("pdf", "9.9.9", Set.of("network")).isAllowed());
    }

    @Test
    void killSwitchBlocksAll() {
        MemoryStore store = new MemoryStore();
        SkillGovernanceService s = service(store, true, true, List.of(), List.of(), true);
        SkillGovernanceService.GovernanceDecision d = s.evaluate("pdf", "1.0.0", Set.of());
        assertFalse(d.isAllowed());
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("熔断")));
    }

    @Test
    void unlistedVersionBlockedWhenAllowlistConfigured() {
        MemoryStore store = new MemoryStore();
        SkillGovernanceService s = service(store, true, false, List.of("pdf@1.0.0"), List.of(), true);
        SkillGovernanceService.GovernanceDecision d = s.evaluate("pdf", "2.0.0", Set.of());
        assertFalse(d.isAllowed());
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("批准清单")));
        // 清单内版本放行
        assertTrue(s.evaluate("pdf", "1.0.0", Set.of()).isAllowed());
    }

    @Test
    void quarantinedVersionBlocked() {
        MemoryStore store = new MemoryStore();
        SkillGovernanceService s = service(store, true, false, List.of(), List.of("pdf@1.0.0"), true);
        assertFalse(s.evaluate("pdf", "1.0.0", Set.of()).isAllowed());
        assertTrue(s.evaluate("pdf", "1.0.1", Set.of()).isAllowed());
    }

    @Test
    void unapprovedPermissionBlocksByDefault() {
        MemoryStore store = new MemoryStore();
        SkillGovernanceService s = service(store, true, false, List.of(), List.of(), true);
        SkillGovernanceService.GovernanceDecision d = s.evaluate("search", "1.0.0", Set.of("network"));
        assertFalse(d.isAllowed());
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("权限未审批")));
    }

    @Test
    void approvedPermissionAllowsExecution() {
        MemoryStore store = new MemoryStore();
        store.approvals.add(new SkillApproval(
                1L, "search", "1.0.0", "network",
                SkillApprovalStore.STATUS_APPROVED, null, "admin", null));
        SkillGovernanceService s = service(store, true, false, List.of(), List.of(), true);
        assertTrue(s.evaluate("search", "1.0.0", Set.of("network")).isAllowed());
    }

    @Test
    void grayModeWarnsInsteadOfBlocking() {
        MemoryStore store = new MemoryStore();
        SkillGovernanceService s = service(store, true, false, List.of(), List.of(), false);
        SkillGovernanceService.GovernanceDecision d = s.evaluate("search", "1.0.0", Set.of("network"));
        assertTrue(d.isAllowed());
        assertTrue(d.warnings().stream().anyMatch(w -> w.contains("权限未审批")));
    }

    @Test
    void auditIsRecorded() {
        MemoryStore store = new MemoryStore();
        SkillGovernanceService s = service(store, true, false, List.of(), List.of(), true);
        s.recordAudit("pdf", "1.0.0", "EXECUTE_BLOCKED", "版本被隔离", true);
        assertFalse(store.audits.isEmpty());
        assertTrue(store.audits.get(0).contains("blocked=true"));
    }
}
