-- 启用向量扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 创建知识库存储表
CREATE TABLE IF NOT EXISTS ai_knowledge_store (
    id VARCHAR(255) PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    embedding vector(1536),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- 创建向量索引
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_embedding 
ON ai_knowledge_store USING hnsw (embedding vector_cosine_ops);

-- 创建元数据索引
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_metadata_type 
ON ai_knowledge_store USING gin ((metadata->'type'));

-- 创建删除时间索引
CREATE INDEX IF NOT EXISTS idx_ai_knowledge_deleted_at 
ON ai_knowledge_store (deleted_at) WHERE deleted_at IS NULL;
