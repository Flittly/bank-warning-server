package com.yangtze.bankwarning.ai.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.yangtze.bankwarning.ai.security.SkillApprovalStore.STATUS_APPROVED;
import static com.yangtze.bankwarning.ai.security.SkillApprovalStore.STATUS_PENDING;

class SkillApprovalServiceTest {

    private static final class MapStore implements SkillApprovalStore {
        final List<SkillApproval> rows = new ArrayList<>();
        final List<AuditRecord> audits = new ArrayList<>();
        long nextId = 1;

        @Override
        public List<SkillApproval> findApprovedPermissions(String skillName, String version) {
            return rows.stream()
                    .filter(a -> a.skillName().equals(skillName) && a.version().equals(version)
                            && STATUS_APPROVED.equals(a.status()))
                    .toList();
        }

        @Override
        public List<SkillApproval> findApprovals(String skillName, String version) {
            return rows.stream()
                    .filter(a -> a.skillName().equals(skillName) && a.version().equals(version))
                    .toList();
        }

        @Override
        public List<SkillApproval> listPending() {
            return rows.stream().filter(a -> STATUS_PENDING.equals(a.status())).toList();
        }

        @Override
        public Optional<SkillApproval> findById(Long id) {
            return rows.stream().filter(a -> id.equals(a.id())).findFirst();
        }

        @Override
        public void createPending(String skillName, String version, String permission, String requestedBy) {
            boolean exists = rows.stream().anyMatch(a ->
                    a.skillName().equals(skillName) && a.version().equals(version) && a.permission().equals(permission));
            if (!exists) {
                rows.add(new SkillApproval(nextId++, skillName, version, permission,
                        STATUS_PENDING, requestedBy, null, null));
            }
        }

        @Override
        public boolean updateStatus(Long id, String status, String reviewer, String comment) {
            Optional<SkillApproval> row = findById(id);
            if (row.isEmpty()) {
                return false;
            }
            rows.remove(row.get());
            SkillApproval a = row.get();
            rows.add(new SkillApproval(a.id(), a.skillName(), a.version(), a.permission(),
                    status, a.requestedBy(), reviewer, comment));
            return true;
        }

        @Override
        public List<AuditRecord> listAudit(int limit) {
            return audits.stream().limit(limit).toList();
        }

        @Override
        public void appendAudit(String skillName, String version, String eventType, String detail, boolean blocked) {
            audits.add(new AuditRecord(skillName, version, eventType, detail, blocked, "now"));
        }
    }

    @Test
    void downloadCreatesPendingForDeclaredPermissions() {
        MapStore store = new MapStore();
        SkillApprovalService service = new SkillApprovalService(store);

        service.createPendingForSkill("search", "1.0.0", Set.of("network", "subprocess"), "alice");

        assertEquals(2, store.listPending().size());
        assertTrue(store.listPending().stream().allMatch(a -> STATUS_PENDING.equals(a.status())));
        assertTrue(store.listPending().stream().allMatch(a -> "alice".equals(a.requestedBy())));
    }

    @Test
    void noPermissionsMeansNoApprovalRows() {
        MapStore store = new MapStore();
        SkillApprovalService service = new SkillApprovalService(store);

        service.createPendingForSkill("pdf", "1.0.0", Set.of(), "alice");

        assertEquals(0, store.listPending().size());
    }

    @Test
    void createPendingIsIdempotent() {
        MapStore store = new MapStore();
        SkillApprovalService service = new SkillApprovalService(store);

        service.createPendingForSkill("search", "1.0.0", Set.of("network"), "alice");
        service.createPendingForSkill("search", "1.0.0", Set.of("network"), "alice");

        assertEquals(1, store.listPending().size());
    }

    @Test
    void approveMakesGovernanceAllowExecution() {
        MapStore store = new MapStore();
        SkillApprovalService approval = new SkillApprovalService(store);
        approval.createPendingForSkill("search", "1.0.0", Set.of("network"), "alice");

        Long id = store.listPending().get(0).id();
        assertTrue(approval.approve(id, "admin"));

        SkillGovernanceService governance = new SkillGovernanceService(
                store, true, false, List.of(), List.of(), true);
        assertTrue(governance.evaluate("search", "1.0.0", Set.of("network")).isAllowed());
        assertFalse(store.listAudit(10).isEmpty());
    }

    @Test
    void rejectKeepsGovernanceBlocking() {
        MapStore store = new MapStore();
        SkillApprovalService approval = new SkillApprovalService(store);
        approval.createPendingForSkill("search", "1.0.0", Set.of("network"), "alice");

        Long id = store.listPending().get(0).id();
        assertTrue(approval.reject(id, "admin", "不允许联网"));

        SkillGovernanceService governance = new SkillGovernanceService(
                store, true, false, List.of(), List.of(), true);
        assertFalse(governance.evaluate("search", "1.0.0", Set.of("network")).isAllowed());
    }

    @Test
    void cannotApproveAlreadyProcessedRecord() {
        MapStore store = new MapStore();
        SkillApprovalService approval = new SkillApprovalService(store);
        approval.createPendingForSkill("search", "1.0.0", Set.of("network"), "alice");
        Long id = store.listPending().get(0).id();

        assertTrue(approval.approve(id, "admin"));
        assertFalse(approval.approve(id, "admin2"));
    }
}
