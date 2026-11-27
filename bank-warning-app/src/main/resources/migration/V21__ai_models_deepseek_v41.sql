-- V21：把内置模型列表收敛成服务商真正存在的两个 id（2026-11-27）
--
-- 本目录下的 V*.sql 只留档，不在 Flyway 内，需要手动执行：
--   psql -h localhost -U postgres -d bank_risk_db -f V21__ai_models_deepseek_v41.sql
--
-- 【为什么要改】
--   旧表里挂了三行：deepseek-v4-flash / deepseek-chat / deepseek-reasoner。
--   其中后两个在服务商的 /v1/models 里**根本不存在**，只是被兼容层接住了：
--
--     GET https://api.deepseek.com/v1/models  → 只有 deepseek-flash、deepseek-v4-pro
--
--   拿旧名发请求仍然 200，但响应体的 model 字段被归一化，等于同一个模型：
--     请求 deepseek-chat      → 响应 model = deepseek-flash
--     请求 deepseek-reasoner  → 响应 model = deepseek-flash
--     请求 deepseek-v4-flash  → 响应 model = deepseek-flash
--
--   也就是说下拉里的「DeepSeek Chat」「DeepSeek Reasoner」是**两个假选项**：
--   选中它们和选「V4 Flash」走的是同一个模型、同一个价、同一份输出。
--   用户以为在换模型，实际什么都没换 —— 这比少一个选项更糟。
--
-- 【为什么不改 model_key，只改 model_name】
--   model_key 是库内 / 前端的稳定标识（唯一约束 ai_models_model_key_key），
--   已经写进用户本地偏好；model_name 才是真正发给服务商的字面量。
--   两者分离后，下次服务商再改名，只需要 UPDATE 一列，不动引用。
--
-- 【幂等性】
--   ON CONFLICT 兜住重复执行；DELETE 对不存在的行是 0 行，同样安全。
--   若目标库里压根没有 deepseek-v4-flash 这一行（自建过别的模型），
--   UPDATE / INSERT 都会影响 0 行，等价于只做了一次清理。
--
-- 执行后的验证（应当恰好 2 行，且 is_default 只有 1 个 t）：
--   SELECT id, model_key, label, model_name, is_default FROM ai_models ORDER BY is_default DESC, id;

BEGIN;

UPDATE ai_models
   SET model_name = 'deepseek-flash',
       label      = 'DeepSeek V4.1 Flash（默认）'
 WHERE model_key = 'deepseek-v4-flash';

-- 复用默认行的 api_key / base_url：同一个服务商、同一把 key，避免抄错或留空。
INSERT INTO ai_models (model_key, label, api_key, base_url, model_name, is_default)
SELECT 'deepseek-v4-pro', 'DeepSeek V4 Pro', api_key, base_url, 'deepseek-v4-pro', false
  FROM ai_models
 WHERE model_key = 'deepseek-v4-flash'
ON CONFLICT (model_key) DO NOTHING;

DELETE FROM ai_models WHERE model_key IN ('deepseek-chat', 'deepseek-reasoner');

COMMIT;

-- 配套的兜底值同步（不在本脚本里，属构建期配置）：
--   application.yml        agentscope.openai.model-name  → ${DEEPSEEK_MODEL:deepseek-flash}
--   application.yml.example 同上
--   AgentScopeConfig.java  @Value 默认值                → deepseek-flash
--   deploy/docker-compose.yml DEEPSEEK_MODEL 默认值     → deepseek-flash
--
-- ⚠ 改完表要重启后端：ModelService 的 modelCache / agentCache 是进程内 ConcurrentHashMap，
--   改库不会让已在运行的实例重新读。
