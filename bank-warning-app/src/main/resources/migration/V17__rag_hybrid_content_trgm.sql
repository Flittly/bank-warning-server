-- V17：阶段 5 混合检索 —— 知识库正文的 trigram 索引（关键词路 sparse 用）
--
-- 本目录下的 V*.sql 只留档，不在 Flyway 内，需要手动执行：
--   psql -h localhost -U postgres -d bank_risk_db -f V17__rag_hybrid_content_trgm.sql
--
-- 什么时候该执行：
--   知识库正文涨到「几百段以上」之后再建。目前只有 4 段 BMS.docx，建倒排索引
--   比全表扫描更浪费（项目已有实测结论：2 行建 trigram 纯属浪费）。
--   不建索引不会让混合检索失效，只是 sparse 那一路走顺序扫描。
--
-- 为什么建在表达式上，而不是直接建在 content 上：
--   ai_knowledge_store.content 存的是 SDK 写入的 JSON 包装
--   {"type":"text","text":"正文…"}，不是纯文本。实测 content ILIKE '%text%'
--   命中 100% 的行 —— 命中的是 JSON 键名。因此 SparseRetriever 的匹配条件是
--   (content::jsonb ->> 'text') ILIKE ?，索引必须建在同一表达式上才会被使用。
--
-- 前提与风险：
--   走 SDK addDocuments() 写入的行恒为 JSON（唯一写入口径）。
--   若有人手工 INSERT 纯文本，content::jsonb 会抛 invalid input syntax for type json；
--   此时 SparseRetriever 会向上抛异常，KnowledgeService 记录 warn 并降级为 dense-only，
--   不会打死检索，但关键词能力会静默失效 —— 所以禁止手工插入非 JSON 的 content。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_ai_knowledge_store_text_trgm
    ON ai_knowledge_store
    USING gin (((content::jsonb ->> 'text')) gin_trgm_ops);

COMMENT ON INDEX idx_ai_knowledge_store_text_trgm IS
    '阶段 5 混合检索：知识库正文 trigram 索引。表达式必须与 SparseRetriever.TEXT_EXPR 一致，否则索引用不上。';

-- 验证索引已生效（应为 Index Scan，而非 Seq Scan）：
--   EXPLAIN ANALYZE
--   SELECT id FROM ai_knowledge_store
--    WHERE (content::jsonb ->> 'text') ILIKE '%Zb%';
