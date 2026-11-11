-- V16__rag_phase2_public_and_traces.sql
-- RAG 阶段 2：公共知识可见性 + 检索留痕表
--
-- 注意：本目录下 V*.sql 只留档、不在 Flyway 内，改 schema 需手动执行（项目既有约定，见 V9 头部注释）。
--       ai_rag_traces 另由 JdbcRagTraceStore 启动时自举建表，两处 DDL 必须保持一致。
--       本脚本可重复执行（IF NOT EXISTS / 幂等 UPDATE）。

-- ---------------------------------------------------------------------------
-- 1) 公共资料标记
-- ---------------------------------------------------------------------------
-- 默认 FALSE = 私有（沿用阶段 1 的「只看自己 user_id」口径）。
-- 规范/规程类资料显式置 TRUE，所有已登录用户可见。
--
-- 为什么单独加列而不是用 user_id IS NULL 表示公共：
--   SecurityUtils.DataScope 与 JdbcReportRunStore 里 null 已经是
--   「管理员 / 不过滤」的语义，复用会把两套含义混在一起；而且会丢掉归属信息。
ALTER TABLE ai_knowledge_store
    ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_ai_knowledge_store_public
    ON ai_knowledge_store (is_public) WHERE is_public = TRUE;

-- 现存 4 段来自 BMS.docx（崩岸判别指标计算口径），属领域规范而非私人文件，
-- 标为公共以便普通账号也能检索到。若不想公开，把 TRUE 改成 FALSE 重跑即可。
UPDATE ai_knowledge_store SET is_public = TRUE WHERE doc_id = 'BMS.docx';

-- ---------------------------------------------------------------------------
-- 2) 检索留痕表
-- ---------------------------------------------------------------------------
-- 一次检索一行：成功与「明确不可用」都记录，用于区分「没查到」与「知识库坏了」。
-- hits / visible / returned 三段计数分别对应粗召回、权限裁剪后、最终返回。
CREATE TABLE IF NOT EXISTS ai_rag_traces (
    id               BIGSERIAL PRIMARY KEY,
    trace_id         VARCHAR(128) NOT NULL,
    scenario         VARCHAR(16)  NOT NULL,
    query_text       TEXT,
    user_id          BIGINT,
    top_k            INT,
    score_threshold  DOUBLE PRECISION,
    hits             INT          NOT NULL DEFAULT 0,
    visible          INT          NOT NULL DEFAULT 0,
    returned         INT          NOT NULL DEFAULT 0,
    hits_json        TEXT,
    status           VARCHAR(24)  NOT NULL,
    degrade_reason   TEXT,
    elapsed_ms       BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_rag_traces_trace_id   ON ai_rag_traces (trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_rag_traces_created_at ON ai_rag_traces (created_at);
