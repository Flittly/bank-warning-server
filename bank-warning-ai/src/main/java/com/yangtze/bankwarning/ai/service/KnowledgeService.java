package com.yangtze.bankwarning.ai.service;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 知识库服务 - 直接使用 VDBStoreBase + EmbeddingModel
 */
@Service
@SuppressWarnings({"deprecation", "removal"})
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final EmbeddingModel embeddingModel;
    private final VDBStoreBase vectorStore;

    public KnowledgeService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                            ObjectProvider<VDBStoreBase> vectorStoreProvider) {
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
        this.vectorStore = vectorStoreProvider.getIfAvailable();
        if (this.embeddingModel == null || this.vectorStore == null) {
            log.warn("知识库向量检索未启用（缺少 EmbeddingModel 或向量库），入库与检索将被跳过");
        }
    }

    /**
     * 添加文档到知识库，为每个文档生成嵌入向量
     */
    @SuppressWarnings("deprecation")
    public Mono<Void> addDocuments(List<Document> docs) {
        if (embeddingModel == null || vectorStore == null) {
            log.warn("向量检索未启用，跳过 {} 个文档的入库", docs == null ? 0 : docs.size());
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            for (Document doc : docs) {
                try {
                    double[] embedding = embeddingModel.embed(doc.getMetadata().getContent()).block();
                    if (embedding != null) {
                        doc.setEmbedding(embedding);
                    }
                } catch (Exception e) {
                    log.error("Failed to generate embedding for document: {}", doc.getId(), e);
                }
            }
            vectorStore.add(docs).block();
            log.info("Added {} documents to knowledge base", docs.size());
        });
    }

    /**
     * 检索相关文档
     */
    @SuppressWarnings("deprecation")
    public Mono<List<Document>> retrieve(String query, int limit, double scoreThreshold) {
        if (embeddingModel == null || vectorStore == null) {
            log.warn("向量检索未启用，返回空结果: {}", query);
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> {
            try {
                var queryBlock = TextBlock.builder().text(query).build();
                double[] queryEmbedding = embeddingModel.embed(queryBlock).block();
                if (queryEmbedding == null) {
                    return List.of();
                }

                SearchDocumentDto searchDto = SearchDocumentDto.builder()
                        .queryEmbedding(queryEmbedding)
                        .limit(limit)
                        .scoreThreshold(scoreThreshold)
                        .build();

                List<Document> results = vectorStore.search(searchDto).block();
                return results != null ? results : List.of();

            } catch (Exception e) {
                log.error("Failed to search knowledge: {}", query, e);
                return List.of();
            }
        });
    }
}