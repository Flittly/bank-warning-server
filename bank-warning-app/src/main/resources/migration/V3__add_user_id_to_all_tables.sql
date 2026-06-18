-- V3__add_user_id_to_all_tables.sql
-- 为所有业务表添加 user_id，实现用户数据隔离

-- ========================================
-- Step 1: 确保默认管理员用户存在
-- ========================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin') THEN
        INSERT INTO users (username, password, phone, email, real_name, role, status)
        VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH',
                '13800138000', 'admin@yangtze.com', '系统管理员', 'ADMIN', 'ACTIVE');
    END IF;
END $$;

-- ========================================
-- Step 2: 为每张表添加 user_id 列
-- ========================================

-- 2.1 banks
ALTER TABLE banks ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.2 tasks
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.3 task_runs
ALTER TABLE task_runs ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.4 basic_params
ALTER TABLE basic_params ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.5 cross_sections
ALTER TABLE cross_sections ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.6 bank_risk_results
ALTER TABLE bank_risk_results ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.7 section_profiles
ALTER TABLE section_profiles ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.8 hydrodynamic_points
ALTER TABLE hydrodynamic_points ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.9 hydrodynamic_data
ALTER TABLE hydrodynamic_data ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.10 tiff_bounds
ALTER TABLE tiff_bounds ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- 2.11 ai_chat_sessions
ALTER TABLE ai_chat_sessions ADD COLUMN IF NOT EXISTS user_id BIGINT;

-- ========================================
-- Step 3: 将所有历史数据归属到 admin
-- ========================================
DO $$
DECLARE
    admin_id BIGINT;
BEGIN
    SELECT id INTO admin_id FROM users WHERE username = 'admin';

    IF admin_id IS NOT NULL THEN
        UPDATE banks SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE tasks SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE task_runs SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE basic_params SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE cross_sections SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE bank_risk_results SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE section_profiles SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE hydrodynamic_points SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE hydrodynamic_data SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE tiff_bounds SET user_id = admin_id WHERE user_id IS NULL;
        UPDATE ai_chat_sessions SET user_id = admin_id WHERE user_id IS NULL;
    END IF;
END $$;

-- ========================================
-- Step 4: 添加 NOT NULL 约束
-- ========================================
ALTER TABLE banks ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE tasks ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE task_runs ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE basic_params ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE cross_sections ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE bank_risk_results ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE section_profiles ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE hydrodynamic_points ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE hydrodynamic_data ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE tiff_bounds ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE ai_chat_sessions ALTER COLUMN user_id SET NOT NULL;

-- ========================================
-- Step 5: 添加外键约束
-- ========================================
ALTER TABLE banks ADD CONSTRAINT fk_banks_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE task_runs ADD CONSTRAINT fk_task_runs_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE basic_params ADD CONSTRAINT fk_basic_params_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE cross_sections ADD CONSTRAINT fk_cross_sections_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE bank_risk_results ADD CONSTRAINT fk_bank_risk_results_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE section_profiles ADD CONSTRAINT fk_section_profiles_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE hydrodynamic_points ADD CONSTRAINT fk_hydrodynamic_points_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE hydrodynamic_data ADD CONSTRAINT fk_hydrodynamic_data_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE tiff_bounds ADD CONSTRAINT fk_tiff_bounds_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE ai_chat_sessions ADD CONSTRAINT fk_ai_chat_sessions_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- ========================================
-- Step 6: 为用户 ID 创建索引
-- ========================================
CREATE INDEX IF NOT EXISTS idx_banks_user_id ON banks(user_id);
CREATE INDEX IF NOT EXISTS idx_tasks_user_id ON tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_task_runs_user_id ON task_runs(user_id);
CREATE INDEX IF NOT EXISTS idx_basic_params_user_id ON basic_params(user_id);
CREATE INDEX IF NOT EXISTS idx_cross_sections_user_id ON cross_sections(user_id);
CREATE INDEX IF NOT EXISTS idx_bank_risk_results_user_id ON bank_risk_results(user_id);
CREATE INDEX IF NOT EXISTS idx_section_profiles_user_id ON section_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_hydrodynamic_points_user_id ON hydrodynamic_points(user_id);
CREATE INDEX IF NOT EXISTS idx_hydrodynamic_data_user_id ON hydrodynamic_data(user_id);
CREATE INDEX IF NOT EXISTS idx_tiff_bounds_user_id ON tiff_bounds(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_chat_sessions_user_id ON ai_chat_sessions(user_id);

-- ========================================
-- ai_knowledge_store (pgvector表，仅添加列和索引，由AgentScope SDK管理)
-- ========================================
ALTER TABLE ai_knowledge_store ADD COLUMN IF NOT EXISTS user_id BIGINT DEFAULT NULL;

DO $$
DECLARE
    admin_id BIGINT;
BEGIN
    SELECT id INTO admin_id FROM users WHERE username = 'admin';
    IF admin_id IS NOT NULL THEN
        UPDATE ai_knowledge_store SET user_id = admin_id WHERE user_id IS NULL;
    END IF;
END $$;

-- ai_knowledge_store user_id 可为空（由AgentScope SDK管理，不强制NOT NULL和外键约束）
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_store_user_id ON ai_knowledge_store(user_id);
