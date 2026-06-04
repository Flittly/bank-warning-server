-- ============================================================
-- AgentScope PgVectorStore 自动建表 DDL（参考文档）
-- ============================================================
-- 注意：PgVectorStore Bean 启动时会自动执行以下 DDL，
-- 无需手动运行此脚本。此文件仅作为表结构文档参考。
-- ============================================================

-- 启用向量扩展（需手动执行一次）
-- CREATE EXTENSION IF NOT EXISTS vector;

-- PgVectorStore 自动创建的表结构：
--   id         VARCHAR(64) PRIMARY KEY   — 唯一标识（uuid）
--   vector     vector(N)                  — 嵌入向量（N 由 dimensions 配置决定）
--   doc_id     VARCHAR(256)               — 文档 ID
--   chunk_id   VARCHAR(256)               — 分块 ID
--   content    TEXT                        — 文本内容
--   payload    JSONB                       — 元数据

-- 自动创建的索引：
--   idx_<table>_doc_id ON <table> (doc_id)
--   idx_<table>_vector ON <table> USING hnsw (vector vector_cosine_ops)
