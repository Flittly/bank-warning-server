-- V19：阶段 3 —— 中文关键词路（jieba 词项 + 倒排索引）
--
-- 本目录下的 V*.sql 只留档，不在 Flyway 内，需要手动执行：
--   psql -h localhost -U postgres -d bank_risk_db -f V19__rag_chinese_search_text.sql
--
-- 背景：PostgreSQL 自带分词器不认中文词边界，一整句「崩岸预警怎么分级」会被切成
-- 一个词项，索引侧与查询侧都如此 ⇒ 关键词路对纯中文提问恒为空。V17 的 trigram 索引
-- 只解决「字面包含」，解决不了「按词命中并排序」。
--
-- 方案：a) 新增 search_text 列，存 jieba 切好的词项（空格分隔，保留重复以保留词频）；
--       b) 在该列上建 GIN 倒排索引，查询走 @@ + ts_rank_cd。
-- 为什么不用 generated column：分词在 Java 侧（jieba），PostgreSQL 里没有等价函数，
-- 只能由应用写入。写入点唯一：KnowledgeService.refreshSearchText（入库后 best-effort 调用）。
--
-- 注意：必须用两参数的 to_tsvector('simple', ...)。单参数的 to_tsvector(text) 是
-- STABLE（依赖 search_path 里的 default_text_search_config），PostgreSQL 拒绝在
-- 它上面建索引；两参数版本是 IMMUTABLE，可以建。
--
-- 索引表达式必须与 SparseRetriever.TSV_EXPR 逐字一致，否则查询用不上索引。
--
-- 执行后的验证：
--   EXPLAIN ANALYZE
--   SELECT id FROM ai_knowledge_store
--    WHERE to_tsvector('simple', search_text) @@ to_tsquery('simple', '崩岸 | 预警');
--   期望见到 Bitmap Index Scan on idx_ai_knowledge_store_search_tsv。

ALTER TABLE ai_knowledge_store ADD COLUMN IF NOT EXISTS search_text text;

COMMENT ON COLUMN ai_knowledge_store.search_text IS
    '关键词路词项（jieba 切分、空格分隔、保留重复以保留词频）。由 KnowledgeService.refreshSearchText 写入，勿手工维护。';

CREATE INDEX IF NOT EXISTS idx_ai_knowledge_store_search_tsv
    ON ai_knowledge_store USING gin (to_tsvector('simple', search_text));

COMMENT ON INDEX idx_ai_knowledge_store_search_tsv IS
    '中文关键词路倒排索引。表达式必须与 SparseRetriever.TSV_EXPR 一致，否则查询退化为顺序扫描。';

-- 存量回填（SQL 里无法分词，必须先跑 Java 侧 CLI）：
--   java -cp "<classes;test-classes;deps>" \
--        com.yangtze.bankwarning.ai.rag.SearchTextBackfillCli --jdbc
-- 回填完成前，search_text 为 NULL 的行不参与中文关键词路（@@ 遇 NULL 判 false），
-- 但仍可被向量路与 ASCII 锚点路召回，不会「消失」。
