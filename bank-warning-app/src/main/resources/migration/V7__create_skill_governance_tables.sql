-- V7__create_skill_governance_tables.sql
-- 阶段三：Skill 治理闭环（权限审批 + 审计留痕）
-- 注意：JdbcSkillApprovalStore 启动时也会自举建表（CREATE TABLE IF NOT EXISTS），
-- 本脚本与自举 DDL 保持一致，供 DBA 手动执行或纳入迁移工具。

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
);

CREATE TABLE IF NOT EXISTS skill_audit_log (
    id BIGSERIAL PRIMARY KEY,
    skill_name VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    detail TEXT,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_skill_audit_log_skill ON skill_audit_log(skill_name, version);
CREATE INDEX IF NOT EXISTS idx_skill_audit_log_created ON skill_audit_log(created_at);
