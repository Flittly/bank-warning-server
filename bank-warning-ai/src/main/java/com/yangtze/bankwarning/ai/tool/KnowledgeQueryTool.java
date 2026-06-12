package com.yangtze.bankwarning.ai.tool;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class KnowledgeQueryTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryTool.class);

    private final Knowledge knowledge;

    public KnowledgeQueryTool(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    @Tool(name = "query_knowledge", description = "查询水利工程领域的法规条文、规范标准、专业术语解释等知识，当用户提出知识性问题时调用此工具")
    public String queryKnowledge(
            @ToolParam(name = "query", description = "知识查询问题描述") String query) {
        log.info("[tool] querying knowledge: {}", query);
        RetrieveConfig config = RetrieveConfig.builder()
                .limit(5)
                .scoreThreshold(0.4)
                .build();
        List<Document> docs = knowledge.retrieve(query, config).block();
        if (docs == null || docs.isEmpty()) {
            return "未找到与「" + query + "」相关的知识。";
        }
        return docs.stream()
                .map(doc -> doc.getMetadata().getContent().toString())
                .collect(Collectors.joining("\n---\n"));
    }
}
