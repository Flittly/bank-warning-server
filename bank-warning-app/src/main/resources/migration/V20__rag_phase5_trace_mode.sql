-- V20：阶段 5 —— 留痕补两列（档位 / 空返回原因）
--
-- 本目录下的 V*.sql 只留档，不在 Flyway 内，需要手动执行：
--   psql -h localhost -U postgres -d bank_risk_db -f V20__rag_phase5_trace_mode.sql
--
-- 背景：ai_rag_traces 建表时只记录「命中多少条」，不记录「这次是哪套配置跑的」，
-- 也不记录「0 条是为什么」。合闸（hybrid/rerank 默认打开）之后，同一个 query 在
-- dense / hybrid / hybrid+rerank 三种档位下结果完全不同，事后比较两次检索
-- 等于在比两套系统；而空列表混在一起，把「库是空的」「被阈值切了」「没权限」说成同一件事。
--
-- mode：dense / dense+rerank / hybrid / hybrid+rerank（见 RagMode）
-- empty_reason：NO_CANDIDATE / BELOW_RECALL_FLOOR / PERMISSION_TRIMMED / BELOW_FINAL_FLOOR
--               （见 RagEmptyReason；两列都允许 NULL，历史行为 null）
--
-- ⚠ 应用启动时 JdbcRagTraceStore 会执行同样的 ALTER ... IF NOT EXISTS 自举，
--   本脚本是给「不想等应用启动」或「手工排查」准备的，两处 DDL 必须保持一致。
--
-- 执行后的验证：
--   \d ai_rag_traces
--   SELECT mode, empty_reason, count(*) FROM ai_rag_traces GROUP BY 1, 2 ORDER BY 3 DESC;

ALTER TABLE ai_rag_traces ADD COLUMN IF NOT EXISTS mode VARCHAR(24);
ALTER TABLE ai_rag_traces ADD COLUMN IF NOT EXISTS empty_reason VARCHAR(32);

COMMENT ON COLUMN ai_rag_traces.mode IS
    '本次检索生效档位：dense / dense+rerank / hybrid / hybrid+rerank。合闸前后数据不可直接比较。';
COMMENT ON COLUMN ai_rag_traces.empty_reason IS
    '成功但返回 0 条的原因：NO_CANDIDATE / BELOW_RECALL_FLOOR / PERMISSION_TRIMMED / BELOW_FINAL_FLOOR。';
