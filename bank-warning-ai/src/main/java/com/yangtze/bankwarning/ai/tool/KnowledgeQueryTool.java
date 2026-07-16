package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.service.KnowledgeService;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings({"deprecation", "removal"})
public class KnowledgeQueryTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryTool.class);

    private final KnowledgeService knowledgeService;

    public KnowledgeQueryTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Tool(name = "query_knowledge", description = "查询水利工程领域的法规条文、规范标准、专业术语解释等知识，当用户提出知识性问题时调用此工具")
    public String queryKnowledge(
            @ToolParam(name = "query", description = "知识查询问题描述") String query) {
        log.info("[tool] querying knowledge: {}", query);
        List<Document> docs = knowledgeService.retrieve(query, 5, 0.4).block();
        if (docs == null || docs.isEmpty()) {
            return "未找到与「" + query + "」相关的知识。";
        }
        return docs.stream()
                .map(doc -> doc.getMetadata().getContent().toString())
                .collect(Collectors.joining("\n---\n"));
    }
}
