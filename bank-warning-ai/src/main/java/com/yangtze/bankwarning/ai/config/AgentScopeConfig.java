package com.yangtze.bankwarning.ai.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.tool.Toolkit;
import com.yangtze.bankwarning.ai.hook.ReasoningTraceHook;
import com.yangtze.bankwarning.ai.service.VisualizationService;
import com.yangtze.bankwarning.ai.tool.RiskDataTools;
import com.yangtze.bankwarning.ai.tool.VisualizationTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AgentScopeConfig {

    @Bean
    public Model deepseekModel(
            @Value("${agentscope.openai.api-key:${DEEPSEEK_API_KEY:}}") String apiKey,
            @Value("${agentscope.openai.base-url:${DEEPSEEK_BASE_URL:https://api.deepseek.com}}") String baseUrl,
            @Value("${agentscope.openai.model-name:${DEEPSEEK_MODEL:deepseek-chat}}") String modelName) {
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/v1/")
                .modelName(modelName)
                .stream(true)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${agentscope.dashscope.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${agentscope.dashscope.embedding-model-name:text-embedding-v3}") String modelName,
            @Value("${agentscope.dashscope.embedding-dimensions:1024}") int dimensions) {
        return DashScopeTextEmbedding.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .dimensions(dimensions)
                .build();
    }

    @Bean
    public VDBStoreBase vectorStore(
            @Value("${agentscope.vector.dimensions:1024}") int dimensions) {
        return InMemoryStore.builder()
                .dimensions(dimensions)
                .build();
    }

    @Bean
    public Knowledge knowledge(EmbeddingModel embeddingModel, VDBStoreBase vectorStore) {
        return SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(vectorStore)
                .build();
    }

    @Bean
    @Qualifier("reportAgent")
    public ObjectMapper reportAgentObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public RiskDataTools riskDataTools(JdbcTemplate jdbcTemplate) {
        return new RiskDataTools(jdbcTemplate);
    }

    @Bean
    public VisualizationTools visualizationTools(VisualizationService vizService) {
        return new VisualizationTools(vizService);
    }

    @Bean
    public Toolkit reportToolkit(RiskDataTools riskDataTools, VisualizationTools visualizationTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(riskDataTools);
        toolkit.registerTool(visualizationTools);
        return toolkit;
    }

    @Bean
    public ReActAgent reportAgent(Model deepseekModel, Toolkit reportToolkit, ReasoningTraceHook traceHook) {
        return ReActAgent.builder()
                .name("ReportAgent")
                .sysPrompt("""
                        你是一名资深的水利工程专家，专门从事长江河岸崩塌风险评估工作。
                        请根据提供的风险评估数据，生成一份专业、严谨的风险评估报告。

                        报告要求：
                        1. 使用中文撰写，语言专业但易懂
                        2. 结构清晰：概述 → 指标分析 → 风险评估 → 建议措施
                        3. 对专业指标进行通俗解释
                        4. 给出明确的风险等级判定依据
                        5. 针对不同风险等级给出相应的应对建议
                        """)
                .model(deepseekModel)
                .memory(new InMemoryMemory())
                .toolkit(reportToolkit)
                .hook(traceHook)
                .maxIters(15)
                .build();
    }

    @Bean
    public ReActAgent qaAgent(Model deepseekModel, Knowledge knowledge) {
        return ReActAgent.builder()
                .name("QAAgent")
                .sysPrompt("""
                        你是一名资深的水利工程专家，专门从事长江河岸崩塌风险评估和防治工作。
                        请根据提供的知识库内容，准确、专业地回答用户的问题。

                        回答要求：
                        1. 基于提供的知识库内容进行回答
                        2. 如果知识库中没有相关信息，请明确说明并给出专业建议
                        3. 回答要专业、准确、易懂
                        """)
                .model(deepseekModel)
                .memory(new InMemoryMemory())
                .toolkit(new Toolkit())
                .knowledge(knowledge)
                .ragMode(RAGMode.AGENTIC)
                .retrieveConfig(RetrieveConfig.builder()
                        .limit(5)
                        .scoreThreshold(0.4)
                        .build())
                .maxIters(5)
                .build();
    }
}
