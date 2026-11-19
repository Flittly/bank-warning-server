-- V18__rag_ingest_idempotent.sql
-- RAG 阶段二：入库幂等 —— 同一篇文档重复上传不再产生第二份
--
-- 本目录下的 V*.sql 只留档，不在 Flyway 内，需要手动执行：
--   psql -h localhost -U postgres -d bank_risk_db -f V18__rag_ingest_idempotent.sql
--
-- 背景（为什么会重复）：
--   ai_knowledge_store 的主键 id 是 SDK 生成的随机 UUID，表上只有 doc_id / chunk_id
--   两个普通 btree 索引，没有任何唯一性约束。于是「同一份文件再上传一次」不是覆盖，
--   而是并排插入一份新的：doc_id、chunk_id 全同，只有 id 不同。检索时两份内容相同的
--   块同时进候选池，互相挤掉别的文档的名额，而且列表页会看到两篇同名文档。
--
-- 代码侧的配套改动（必须与本脚本一起上线，否则唯一索引会让写入直接失败）：
--   KnowledgeController 上传/文本导入都改成「先写临时 doc_id → 整批成功 → 删旧 + 换名」，
--   见 KnowledgeService.replaceStaged / discardStaged。
--   换名发生在 DELETE 之后，所以不会与下面的唯一索引冲突。
--
-- 执行顺序：
--   1) 先跑本脚本（清历史重复 + 建唯一索引）
--   2) 再发布应用（新代码不再产生重复）
--   反过来不会出错，只是在发布前仍然可能继续累积重复行。

-- ---------------------------------------------------------------------------
-- 1) 清理历史重复：同 (doc_id, chunk_id) 只保留物理上最后写入的一行
-- ---------------------------------------------------------------------------
-- 用 ctid（行物理位置）判断先后，不依赖 id（UUID 无序）也不依赖 created_at（本表没有该列）。
-- 重复行的 content 来自同一次切分，内容一致，保留哪一行都不影响检索结果。
DELETE FROM ai_knowledge_store a
USING ai_knowledge_store b
WHERE a.ctid < b.ctid
  AND a.doc_id IS NOT DISTINCT FROM b.doc_id
  AND a.chunk_id IS NOT DISTINCT FROM b.chunk_id;

-- ---------------------------------------------------------------------------
-- 2) 唯一约束：数据库层兜底，不依赖代码自觉
-- ---------------------------------------------------------------------------
-- chunk_id 允许为 NULL（SDK 建表时该列可空，SparseRetriever 用主键兜底），
-- 而 PostgreSQL 的唯一索引允许多个 NULL，历史遗留的 NULL 行不会被这个索引挡住。
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_knowledge_store_doc_chunk
    ON ai_knowledge_store (doc_id, chunk_id);

COMMENT ON INDEX uq_ai_knowledge_store_doc_chunk IS
    '阶段二：入库幂等。同一 doc_id 下 chunk_id 不可重复，重复上传必须走「删旧+换名」而不是追加。';

-- ---------------------------------------------------------------------------
-- 3) 验证（执行后应返回 0 行）
-- ---------------------------------------------------------------------------
-- SELECT doc_id, chunk_id, count(*)
--   FROM ai_knowledge_store
--  WHERE chunk_id IS NOT NULL
--  GROUP BY doc_id, chunk_id
-- HAVING count(*) > 1;
--
-- 索引已生效（应为 Index Scan / Bitmap Index Scan）：
-- EXPLAIN SELECT 1 FROM ai_knowledge_store WHERE doc_id = 'BMS.docx' AND chunk_id = 'BMS.docx_chunk_0';
