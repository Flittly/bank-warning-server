package com.yangtze.bankwarning.ai.store;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的 Skill 版本存储。
 *
 * 启动时自举建表（与 migration/V8 脚本一致）；ACTIVE 唯一性用
 * PostgreSQL 部分唯一索引保证（同一 skill 只有一个 ACTIVE）。
 */
@Component
public class JdbcSkillVersionStore implements SkillVersionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSkillVersionStore.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcSkillVersionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
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
                )""");
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_skill_versions_active
                    ON skill_versions(skill_name) WHERE status = 'ACTIVE'
                """);
        log.info("[skill-versions] skill_versions 表已就绪");
    }

    @Override
    public Optional<SkillVersion> findActive(String skillName) {
        return jdbcTemplate.query(
                "SELECT id, skill_name, version, source, status, downloaded_at, activated_at, updated_by, updated_at "
                        + "FROM skill_versions WHERE skill_name = ? AND status = ? ORDER BY activated_at DESC LIMIT 1",
                ROW_MAPPER, skillName, STATUS_ACTIVE).stream().findFirst();
    }

    @Override
    public Optional<SkillVersion> findByVersion(String skillName, String version) {
        return jdbcTemplate.query(
                "SELECT id, skill_name, version, source, status, downloaded_at, activated_at, updated_by, updated_at "
                        + "FROM skill_versions WHERE skill_name = ? AND version = ?",
                ROW_MAPPER, skillName, version).stream().findFirst();
    }

    @Override
    public List<SkillVersion> listVersions(String skillName) {
        return jdbcTemplate.query(
                "SELECT id, skill_name, version, source, status, downloaded_at, activated_at, updated_by, updated_at "
                        + "FROM skill_versions WHERE skill_name = ? ORDER BY activated_at DESC NULLS LAST, id DESC",
                ROW_MAPPER, skillName);
    }

    @Override
    public List<SkillVersion> listAll() {
        return jdbcTemplate.query(
                "SELECT id, skill_name, version, source, status, downloaded_at, activated_at, updated_by, updated_at "
                        + "FROM skill_versions ORDER BY updated_at DESC, id DESC",
                ROW_MAPPER);
    }

    @Override
    public synchronized SkillVersion registerOrActivate(String skillName, String version, String source, String updatedBy) {
        // 先降级同 skill 的其他 ACTIVE 版本，再激活目标版本：
        // 部分唯一索引 uq_skill_versions_active 在同一时刻只允许一个 ACTIVE，
        // 顺序反了会在“更新目标为 ACTIVE”这一步撞索引。
        deactivateOthers(skillName, version);
        jdbcTemplate.update("""
                INSERT INTO skill_versions(skill_name, version, source, status, activated_at, updated_by, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (skill_name, version) DO UPDATE
                    SET source = EXCLUDED.source,
                        status = 'ACTIVE',
                        activated_at = CURRENT_TIMESTAMP,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = CURRENT_TIMESTAMP
                """, skillName, version, source, STATUS_ACTIVE, updatedBy);
        return findByVersion(skillName, version).orElseThrow();
    }

    @Override
    public synchronized boolean activate(String skillName, String version, String updatedBy) {
        // 版本不存在时不动其他 ACTIVE（避免误把当前生效版本降级）
        if (findByVersion(skillName, version).isEmpty()) {
            return false;
        }
        deactivateOthers(skillName, version);
        int rows = jdbcTemplate.update("""
                UPDATE skill_versions
                   SET status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP,
                       updated_by = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE skill_name = ? AND version = ?
                """, updatedBy, skillName, version);
        return rows > 0;
    }

    @Override
    public synchronized boolean setStatus(String skillName, String version, String status, String updatedBy) {
        if (STATUS_ACTIVE.equals(status)) {
            return activate(skillName, version, updatedBy);
        }
        int rows = jdbcTemplate.update(
                "UPDATE skill_versions SET status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE skill_name = ? AND version = ?",
                status, updatedBy, skillName, version);
        return rows > 0;
    }

    @Override
    public synchronized boolean delete(String skillName, String version) {
        return jdbcTemplate.update(
                "DELETE FROM skill_versions WHERE skill_name = ? AND version = ?",
                skillName, version) > 0;
    }

    /** 把同 skill 其他 ACTIVE 版本降级为 RETIRED（保证唯一 ACTIVE） */
    private void deactivateOthers(String skillName, String version) {
        jdbcTemplate.update("""
                UPDATE skill_versions
                   SET status = 'RETIRED', updated_at = CURRENT_TIMESTAMP
                 WHERE skill_name = ? AND version <> ? AND status = 'ACTIVE'
                """, skillName, version);
    }

    private static final RowMapper<SkillVersion> ROW_MAPPER = (rs, rowNum) -> new SkillVersion(
            rs.getLong("id"),
            rs.getString("skill_name"),
            rs.getString("version"),
            rs.getString("source"),
            rs.getString("status"),
            stringify(rs.getTimestamp("downloaded_at")),
            stringify(rs.getTimestamp("activated_at")),
            rs.getString("updated_by"),
            stringify(rs.getTimestamp("updated_at")));

    private static String stringify(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toString();
    }
}
