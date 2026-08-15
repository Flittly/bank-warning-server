-- V8__create_skill_versions_table.sql
-- 档位二：Skill 本地多版本共存
-- 注意：JdbcSkillVersionStore 启动时也会自举建表（CREATE TABLE IF NOT EXISTS），
-- 本脚本与自举 DDL 保持一致，供 DBA 手动执行或纳入迁移工具。

CREATE TABLE IF NOT EXISTS skill_versions (
    id BIGSERIAL PRIMARY KEY,
    skill_name VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'nacos',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    downloaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(skill_name, version)
);

-- 同一 skill 同一时刻只允许一个 ACTIVE 版本（PostgreSQL 部分唯一索引）
CREATE UNIQUE INDEX IF NOT EXISTS uq_skill_versions_active
    ON skill_versions(skill_name) WHERE status = 'ACTIVE';
