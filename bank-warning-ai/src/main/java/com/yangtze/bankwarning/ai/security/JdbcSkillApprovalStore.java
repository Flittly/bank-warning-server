package com.yangtze.bankwarning.ai.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的 Skill 审批/审计存储。
 *
 * 启动时自动建表（CREATE TABLE IF NOT EXISTS，与 migration/V7 脚本一致），
 * 不依赖外部迁移工具也能自举。
 */
@Component
public class JdbcSkillApprovalStore implements SkillApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSkillApprovalStore.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcSkillApprovalStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS skill_approvals (
                    id BIGSERIAL PRIMARY KEY,
                    skill_name VARCHAR(128) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    permission VARCHAR(64) NOT NULL,
                    approved BOOLEAN NOT NULL DEFAULT FALSE,
                    status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
                    requested_by VARCHAR(128),
                    reviewed_by VARCHAR(128),
                    reviewed_at TIMESTAMP,
                    comment TEXT,
                    approved_by VARCHAR(128),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(skill_name, version, permission)
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS skill_audit_log (
                    id BIGSERIAL PRIMARY KEY,
                    skill_name VARCHAR(128) NOT NULL,
                    version VARCHAR(64) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    detail TEXT,
                    blocked BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");
        // 兼容旧表：补齐产品化审批流需要的新列（已存在的表不会重建）
        jdbcTemplate.execute("ALTER TABLE skill_approvals ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'APPROVED'");
        jdbcTemplate.execute("ALTER TABLE skill_approvals ADD COLUMN IF NOT EXISTS requested_by VARCHAR(128)");
        jdbcTemplate.execute("ALTER TABLE skill_approvals ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(128)");
        jdbcTemplate.execute("ALTER TABLE skill_approvals ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE skill_approvals ADD COLUMN IF NOT EXISTS comment TEXT");
        log.info("[skill-governance] 审批/审计表已就绪");
    }

    @Override
    public List<SkillApproval> findApprovedPermissions(String skillName, String version) {
        return jdbcTemplate.query(
                "SELECT id, skill_name, version, permission, status, requested_by, reviewed_by, comment FROM skill_approvals "
                        + "WHERE skill_name = ? AND version = ? AND status = ?",
                (rs, rowNum) -> new SkillApproval(
                        rs.getLong("id"),
                        rs.getString("skill_name"),
                        rs.getString("version"),
                        rs.getString("permission"),
                        rs.getString("status"),
                        rs.getString("requested_by"),
                        rs.getString("reviewed_by"),
                        rs.getString("comment")),
                skillName, version, STATUS_APPROVED);
    }

    @Override
    public List<SkillApproval> findApprovals(String skillName, String version) {
        return jdbcTemplate.query(
                "SELECT id, skill_name, version, permission, status, requested_by, reviewed_by, comment FROM skill_approvals "
                        + "WHERE skill_name = ? AND version = ? ORDER BY created_at DESC",
                ROW_MAPPER, skillName, version);
    }

    @Override
    public List<SkillApproval> listPending() {
        return jdbcTemplate.query(
                "SELECT id, skill_name, version, permission, status, requested_by, reviewed_by, comment FROM skill_approvals "
                        + "WHERE status = ? ORDER BY created_at DESC",
                ROW_MAPPER, STATUS_PENDING);
    }

    @Override
    public Optional<SkillApproval> findById(Long id) {
        List<SkillApproval> rows = jdbcTemplate.query(
                "SELECT id, skill_name, version, permission, status, requested_by, reviewed_by, comment FROM skill_approvals "
                        + "WHERE id = ?",
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    public void createPending(String skillName, String version, String permission, String requestedBy) {
        jdbcTemplate.update(
                "INSERT INTO skill_approvals(skill_name, version, permission, approved, status, requested_by) "
                        + "VALUES (?, ?, ?, FALSE, ?, ?) "
                        + "ON CONFLICT (skill_name, version, permission) DO NOTHING",
                skillName, version, permission, STATUS_PENDING, requestedBy);
    }

    @Override
    public boolean updateStatus(Long id, String status, String reviewer, String comment) {
        boolean approved = STATUS_APPROVED.equals(status);
        int rows = jdbcTemplate.update(
                "UPDATE skill_approvals SET status = ?, approved = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, comment = ? "
                        + "WHERE id = ?",
                status, approved, reviewer, comment, id);
        return rows > 0;
    }

    @Override
    public List<AuditRecord> listAudit(int limit) {
        return jdbcTemplate.query(
                "SELECT skill_name, version, event_type, detail, blocked, created_at FROM skill_audit_log "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new AuditRecord(
                        rs.getString("skill_name"),
                        rs.getString("version"),
                        rs.getString("event_type"),
                        rs.getString("detail"),
                        rs.getBoolean("blocked"),
                        String.valueOf(rs.getTimestamp("created_at"))),
                limit);
    }

    @Override
    public void appendAudit(String skillName, String version, String eventType, String detail, boolean blocked) {
        jdbcTemplate.update(
                "INSERT INTO skill_audit_log(skill_name, version, event_type, detail, blocked) VALUES (?, ?, ?, ?, ?)",
                skillName, version, eventType, detail, blocked);
    }

    private static final org.springframework.jdbc.core.RowMapper<SkillApproval> ROW_MAPPER =
            (rs, rowNum) -> new SkillApproval(
                    rs.getLong("id"),
                    rs.getString("skill_name"),
                    rs.getString("version"),
                    rs.getString("permission"),
                    rs.getString("status"),
                    rs.getString("requested_by"),
                    rs.getString("reviewed_by"),
                    rs.getString("comment"));
}
