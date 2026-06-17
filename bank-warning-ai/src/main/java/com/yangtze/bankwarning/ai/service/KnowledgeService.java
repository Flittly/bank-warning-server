package com.yangtze.bankwarning.ai.service;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public KnowledgeService(EmbeddingModel embeddingModel, VDBStoreBase vectorStore) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
    }

    /**
     * 添加文档到知识库，为每个文档生成嵌入向量
     */
    @SuppressWarnings("deprecation")
    public Mono<Void> addDocuments(List<Document> docs) {
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
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        return Mono.fromCallable(() -> {
            try {
                var queryBlock = TextBlock.builder().text(query).build();
                double[] queryEmbedding = embeddingModel.embed(queryBlock).block();
                if (queryEmbedding == null) {
                    return List.of();
                }

                SearchDocumentDto searchDto = SearchDocumentDto.builder()
                        .queryEmbedding(queryEmbedding)
                        .limit(config.getLimit())
                        .scoreThreshold(config.getScoreThreshold())
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