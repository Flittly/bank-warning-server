package com.yangtze.bankwarning.ai.store;

import com.yangtze.bankwarning.ai.security.SkillSecurityProfile;
import com.yangtze.bankwarning.ai.security.SkillSecuritySettings;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 安全档位的 JDBC 存储（PostgreSQL）。
 *
 * <p>启动时自建表，与 {@link JdbcSkillApprovalStore} 的既有做法一致，
 * 不依赖外部迁移工具也能自举。
 */
@Component
public class JdbcSkillSecurityProfileStore implements SkillSecurityProfileStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSkillSecurityProfileStore.class);

    /** 全局档位固定为这一行 */
    private static final int SINGLETON_ID = 1;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSkillSecurityProfileStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS skill_security_profile (
                    id SMALLINT PRIMARY KEY,
                    profile VARCHAR(16) NOT NULL,
                    verify_enabled BOOLEAN NOT NULL,
                    fail_on_violation BOOLEAN NOT NULL,
                    forbidden_imports TEXT,
                    require_checksum_manifest BOOLEAN NOT NULL,
                    require_signed_manifest BOOLEAN NOT NULL,
                    sandbox_mode VARCHAR(16) NOT NULL,
                    sandbox_timeout_seconds INT NOT NULL,
                    sandbox_max_output_bytes INT NOT NULL,
                    sandbox_memory_mb INT NOT NULL,
                    sandbox_cpu_seconds INT NOT NULL,
                    sandbox_pids_limit INT NOT NULL,
                    governance_enabled BOOLEAN NOT NULL,
                    allowed_versions TEXT,
                    quarantined_versions TEXT,
                    fail_on_unapproved_permission BOOLEAN NOT NULL,
                    enforce_output_contract BOOLEAN NOT NULL,
                    kill_switch BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_by VARCHAR(128),
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");
        log.info("[skill-security] 安全档位表已就绪");
    }

    @Override
    public Optional<SkillSecuritySettings> load() {
        List<SkillSecuritySettings> rows = jdbcTemplate.query(
                "SELECT profile, verify_enabled, fail_on_violation, forbidden_imports, "
                        + "require_checksum_manifest, require_signed_manifest, sandbox_mode, "
                        + "sandbox_timeout_seconds, sandbox_max_output_bytes, sandbox_memory_mb, "
                        + "sandbox_cpu_seconds, sandbox_pids_limit, governance_enabled, allowed_versions, "
                        + "quarantined_versions, fail_on_unapproved_permission, enforce_output_contract, "
                        + "kill_switch, updated_by, updated_at "
                        + "FROM skill_security_profile WHERE id = ?",
                (rs, rowNum) -> new SkillSecuritySettings(
                        SkillSecurityProfile.parse(rs.getString("profile"), SkillSecurityProfile.STANDARD),
                        rs.getBoolean("verify_enabled"),
                        rs.getBoolean("fail_on_violation"),
                        split(rs.getString("forbidden_imports")),
                        rs.getBoolean("require_checksum_manifest"),
                        rs.getBoolean("require_signed_manifest"),
                        rs.getString("sandbox_mode"),
                        rs.getInt("sandbox_timeout_seconds"),
                        rs.getInt("sandbox_max_output_bytes"),
                        rs.getInt("sandbox_memory_mb"),
                        rs.getInt("sandbox_cpu_seconds"),
                        rs.getInt("sandbox_pids_limit"),
                        rs.getBoolean("governance_enabled"),
                        split(rs.getString("allowed_versions")),
                        split(rs.getString("quarantined_versions")),
                        rs.getBoolean("fail_on_unapproved_permission"),
                        rs.getBoolean("enforce_output_contract"),
                        rs.getBoolean("kill_switch"),
                        rs.getString("updated_by"),
                        String.valueOf(rs.getTimestamp("updated_at"))),
                SINGLETON_ID);
        return rows.stream().findFirst();
    }

    @Override
    public void save(SkillSecuritySettings settings, String actor) {
        jdbcTemplate.update(
                "INSERT INTO skill_security_profile ("
                        + "id, profile, verify_enabled, fail_on_violation, forbidden_imports, "
                        + "require_checksum_manifest, require_signed_manifest, sandbox_mode, "
                        + "sandbox_timeout_seconds, sandbox_max_output_bytes, sandbox_memory_mb, "
                        + "sandbox_cpu_seconds, sandbox_pids_limit, governance_enabled, allowed_versions, "
                        + "quarantined_versions, fail_on_unapproved_permission, enforce_output_contract, "
                        + "kill_switch, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "profile = EXCLUDED.profile, "
                        + "verify_enabled = EXCLUDED.verify_enabled, "
                        + "fail_on_violation = EXCLUDED.fail_on_violation, "
                        + "forbidden_imports = EXCLUDED.forbidden_imports, "
                        + "require_checksum_manifest = EXCLUDED.require_checksum_manifest, "
                        + "require_signed_manifest = EXCLUDED.require_signed_manifest, "
                        + "sandbox_mode = EXCLUDED.sandbox_mode, "
                        + "sandbox_timeout_seconds = EXCLUDED.sandbox_timeout_seconds, "
                        + "sandbox_max_output_bytes = EXCLUDED.sandbox_max_output_bytes, "
                        + "sandbox_memory_mb = EXCLUDED.sandbox_memory_mb, "
                        + "sandbox_cpu_seconds = EXCLUDED.sandbox_cpu_seconds, "
                        + "sandbox_pids_limit = EXCLUDED.sandbox_pids_limit, "
                        + "governance_enabled = EXCLUDED.governance_enabled, "
                        + "allowed_versions = EXCLUDED.allowed_versions, "
                        + "quarantined_versions = EXCLUDED.quarantined_versions, "
                        + "fail_on_unapproved_permission = EXCLUDED.fail_on_unapproved_permission, "
                        + "enforce_output_contract = EXCLUDED.enforce_output_contract, "
                        + "kill_switch = EXCLUDED.kill_switch, "
                        + "updated_by = EXCLUDED.updated_by, "
                        + "updated_at = CURRENT_TIMESTAMP",
                SINGLETON_ID,
                settings.profile().name(),
                settings.verifyEnabled(),
                settings.failOnViolation(),
                join(settings.forbiddenImports()),
                settings.requireChecksumManifest(),
                settings.requireSignedManifest(),
                settings.normalizedMode(),
                settings.sandboxTimeoutSeconds(),
                settings.sandboxMaxOutputBytes(),
                settings.sandboxMemoryMb(),
                settings.sandboxCpuSeconds(),
                settings.sandboxPidsLimit(),
                settings.governanceEnabled(),
                join(settings.allowedVersions()),
                join(settings.quarantinedVersions()),
                settings.failOnUnapprovedPermission(),
                settings.enforceOutputContract(),
                settings.killSwitch(),
                actor);
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return List.copyOf(parts);
    }
}
