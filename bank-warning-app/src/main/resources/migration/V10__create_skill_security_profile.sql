-- V10__create_skill_security_profile.sql
-- Skill 安全档位（三层防护的统一策略源）
--
-- 背景：阶段一（校验/静态扫描）、阶段二（执行隔离）、阶段三（治理闭环）的十几个开关
--       过去只存在于 application.yml，改一次要重启，且 governance / sandbox 两个节点
--       在原 yml 里根本不存在（全靠 @Value 兜底），运维无从发现。
--       现在全部收敛到这张单行表，由 /v0/admin/skill-security 端点读写，立即生效、免重启。
--
-- 注意：JdbcSkillSecurityProfileStore 启动时也会自举建表（CREATE TABLE IF NOT EXISTS），
--       本脚本与自举 DDL 保持一致，供 DBA 手动执行或纳入迁移工具。
--
-- 为什么每个开关独立成列、而不是塞一个 JSON blob：
--       这张表的价值是"事后可审计"与"能被 SQL 直接比对"，独立列让 DBA 一眼看出
--       某个时刻哪一项被放宽、被谁、什么时候。JSON 会让这件事退化成解析字符串。

CREATE TABLE IF NOT EXISTS skill_security_profile (
    -- 全局配置固定一行，id 恒为 1
    id SMALLINT PRIMARY KEY,

    -- 所属档位：LOOSE / STANDARD / STRICT（逐项微调后标签不变，用于回显与"是否偏离预设"判断）
    profile VARCHAR(16) NOT NULL,

    -- 阶段一：校验与静态扫描
    verify_enabled BOOLEAN NOT NULL,
    fail_on_violation BOOLEAN NOT NULL,
    forbidden_imports TEXT,
    require_checksum_manifest BOOLEAN NOT NULL,
    require_signed_manifest BOOLEAN NOT NULL,

    -- 阶段二：执行隔离
    sandbox_mode VARCHAR(16) NOT NULL,
    sandbox_timeout_seconds INT NOT NULL,
    sandbox_max_output_bytes INT NOT NULL,
    sandbox_memory_mb INT NOT NULL,
    sandbox_cpu_seconds INT NOT NULL,
    sandbox_pids_limit INT NOT NULL,

    -- 阶段三：治理闭环
    governance_enabled BOOLEAN NOT NULL,
    allowed_versions TEXT,
    quarantined_versions TEXT,
    fail_on_unapproved_permission BOOLEAN NOT NULL,
    enforce_output_contract BOOLEAN NOT NULL,

    -- 应急开关：不属于任何预设，只能由管理员显式操作（切档位时原样保留）
    kill_switch BOOLEAN NOT NULL DEFAULT FALSE,

    -- 审计元信息
    updated_by VARCHAR(128),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 播种：首次启动时由 SkillSecurityService 按 STANDARD 预设写入，
-- STANDARD 的每一项取值刻意等于改造前的既有默认值，保证"首次落库 = 行为不变"。
-- 这里不预置数据，避免与应用的首次播种逻辑产生两条真相源。
