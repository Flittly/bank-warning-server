package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.KnowledgeUnavailableException;
import com.yangtze.bankwarning.ai.service.SourceCitation;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@SuppressWarnings({"deprecation", "removal"})
public class KnowledgeQueryTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryTool.class);

    private final KnowledgeService knowledgeService;

    public KnowledgeQueryTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 身份参数 {@code caller} 故意不加 {@code @ToolParam}：框架按类型从
     * RuntimeContext 注入，模型既看不见也改不了（见 AiCaller 的说明）。
     * 工具方法不抛异常打断 ReAct 循环，而是把不可用原因作为文本回给模型。
     */
    @Tool(name = "query_knowledge", description = "查询水利工程领域的法规条文、规范标准、专业术语解释等知识，当用户提出知识性问题时调用此工具。返回值带编号与来源，回答时必须按编号标注依据；问题含指代（如「那它呢」）时，请结合上文改写成完整问句再查询")
    public String queryKnowledge(
            @ToolParam(name = "query", description = "知识查询问题描述") String query,
            AiCaller caller) {
        log.info("[tool] querying knowledge: {}", query);
        try {
            List<Document> docs = knowledgeService.retrieve(query, KnowledgeService.Scenario.TOOL, caller);
            if (docs.isEmpty()) {
                return "未找到与「" + query + "」相关的知识。请如实告知用户知识库中没有该问题的依据，"
                        + "不要用常识代替规范条文，也不要把近似内容说成依据。";
            }
            // 阶段 4：编号 + 来源 + 契约三件套。只给正文，模型既说不出出处，也无从判断
            // 「这条到底是不是用户问的那份文件」。
            return SourceCitation.render(docs) + SourceCitation.RULE;
        } catch (KnowledgeUnavailableException e) {
            log.warn("[tool] 知识库不可用（{}）: {}", e.reason(), e.getMessage());
            return "知识库当前不可用（" + e.reason() + "）：" + e.getMessage()
                    + "。请如实告知用户当前无法查证，不要编造规范条文或指标依据。";
        }
    }
}
