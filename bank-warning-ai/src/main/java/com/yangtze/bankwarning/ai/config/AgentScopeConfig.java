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
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.store.PgVectorStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import com.yangtze.bankwarning.ai.hook.ReasoningTraceHook;
import com.yangtze.bankwarning.ai.service.VisualizationService;
import com.yangtze.bankwarning.ai.service.WeatherService;
import com.yangtze.bankwarning.ai.tool.RiskDataTools;
import com.yangtze.bankwarning.ai.tool.VisualizationTools;
import com.yangtze.bankwarning.ai.tool.WeatherTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yangtze.bankwarning.ai.service.PdfService;
import com.yangtze.bankwarning.ai.tool.PdfTools;

@Configuration
public class AgentScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

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
            @Value("${agentscope.vector.dimensions:1024}") int dimensions,
            @Value("${agentscope.vector.pgvector.jdbc-url:${BANK_DB_URL:jdbc:postgresql://localhost:5432/bank_risk_db}}") String jdbcUrl,
            @Value("${agentscope.vector.pgvector.username:${BANK_DB_USERNAME:postgres}}") String username,
            @Value("${agentscope.vector.pgvector.password:${BANK_DB_PASSWORD:123456}}") String password,
            @Value("${agentscope.vector.pgvector.schema:public}") String schema,
            @Value("${agentscope.vector.pgvector.table-name:ai_knowledge_store}") String tableName) {
        try {
            return PgVectorStore.builder()
                    .jdbcUrl(jdbcUrl)
                    .username(username)
                    .password(password)
                    .schema(schema)
                    .tableName(tableName)
                    .dimensions(dimensions)
                    .distanceType(PgVectorStore.DistanceType.COSINE)
                    .build();
        } catch (VectorStoreException e) {
            throw new RuntimeException("Failed to initialize pgvector store", e);
        }
    }

    @Bean
    public Knowledge knowledge(EmbeddingModel embeddingModel, VDBStoreBase vectorStore) {
        return SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(vectorStore)
                .build();
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
    public WeatherTools weatherTools(WeatherService weatherService) {
        return new WeatherTools(weatherService);
    }

    @Bean
    public PdfTools pdfTools(PdfService pdfService) {
        return new PdfTools(pdfService);
    }

    @Bean
    public Toolkit reportToolkit(RiskDataTools riskDataTools,
                                 VisualizationTools visualizationTools,
                                 WeatherTools weatherTools,
                                 PdfTools pdfTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(riskDataTools);
        toolkit.registerTool(visualizationTools);
        toolkit.registerTool(weatherTools);
        toolkit.registerTool(pdfTools);
        return toolkit;
    }

    @Bean
    public SkillBox skillBox(Toolkit reportToolkit,
                             org.springframework.beans.factory.ObjectProvider<NacosSkillRepositoryHolder> nacosHolderProvider,
                             @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}") String cacheDir) throws Exception {
        SkillBox skillBox = new SkillBox(reportToolkit);
        java.util.Set<String> registered = new java.util.HashSet<>();
        java.nio.file.Path cacheBase = java.nio.file.Paths.get(cacheDir);

        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            for (AgentSkill skill : repo.getAllSkills()) {
                String name = skill.getName();
                if (registered.add(name)) {
                    materializeSkillScripts(skill, cacheBase);
                    skillBox.registration().skill(skill).apply();
                    log.info("[SkillBox] registered local: {}", name);
                } else {
                    log.warn("[SkillBox] duplicate local skill skipped: {}", name);
                }
            }
        }

        NacosSkillRepositoryHolder nacosHolder = nacosHolderProvider.getIfAvailable();
        if (nacosHolder != null && nacosHolder.isAvailable()) {
            for (AgentSkill skill : nacosHolder.getRepository().getAllSkills()) {
                String name = skill.getName();
                if (registered.add(name)) {
                    materializeSkillScripts(skill, cacheBase);
                    skillBox.registration().skill(skill).apply();
                    log.info("[SkillBox] registered nacos: {}", name);
                } else {
                    log.info("[SkillBox] nacos skill '{}' skipped (local has higher priority)", name);
                }
            }
        } else {
            log.info("[SkillBox] Nacos not configured or unreachable, using local skills only");
        }
        return skillBox;
    }

    private void materializeSkillScripts(AgentSkill skill, java.nio.file.Path cacheBase) {
        java.util.Map<String, String> resources = skill.getResources();
        if (resources == null || resources.isEmpty()) return;
        java.nio.file.Path base = cacheBase.resolve(skill.getName());
        for (java.util.Map.Entry<String, String> entry : resources.entrySet()) {
            String relPath = entry.getKey();
            String content = entry.getValue();
            if (content == null) continue;
            java.nio.file.Path target = base.resolve(relPath);
            try {
                java.nio.file.Files.createDirectories(target.getParent());
                String decoded = content.startsWith("base64:")
                        ? new String(java.util.Base64.getDecoder().decode(content.substring("base64:".length())))
                        : content;
                java.nio.file.Files.writeString(target, decoded,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                log.info("[SkillBox] materialized: {}", target);
            } catch (Exception e) {
                log.error("[SkillBox] failed to materialize {}: {}", target, e.getMessage());
            }
        }
    }

    @Bean
    @Qualifier("chatAgent")
    public ReActAgent chatAgent(Model deepseekModel, Toolkit reportToolkit,
                                SkillBox skillBox, Knowledge knowledge,
                                ReasoningTraceHook traceHook) {
        String skillPrompt = skillBox.getSkillPrompt();
        return ReActAgent.builder()
                .name("ChatAgent")
                .sysPrompt("""
                        你是一名资深的水利工程专家，专门从事长江河岸崩塌风险评估工作。
                        你拥有工具和知识库两套能力，请根据用户的问题自主选择使用。

                        === 工具能力（查询实时数据、生成图表） ===
                        你有以下工具可以调用：

                        1. query_risk_data(task_id) — 查询指定任务的断面风险评估数据
                           用户可能在问题中直接提到任务编号（如"12345"），请从中提取 task_id 并调用
                        2. generate_risk_distribution_map(task_id) — 生成风险分布图
                        3. generate_scour_heatmap(section_id) — 生成冲淤热力图
                        4. generate_section_comparison_chart(section_id) — 生成断面对比图
                        5. get_weather_forecast(lng, lat, days) — 查询未来 N 天天气
                        6. get_weather_warning(lng, lat) — 查询当前天气预警
                        7. process_pdf(skillName, scriptName, filePath) — 处理 PDF 文件（skillName 必填，如 "pdf"）

                        === 知识库能力（查询水利法规、规范、历史案例） ===
                        如果用户询问的是法规条文、规范标准、专业术语解释等知识性问题，
                        无需调用工具，直接使用知识库检索即可回答。

                        === 报告生成流程 ===
                        如果用户要求生成风险评估报告，请按以下步骤执行：
                        1. 从用户问题中提取 task_id，调用 query_risk_data(task_id) 获取数据
                        2. 调用 generate_risk_distribution_map(task_id) 生成风险分布图
                        3. 筛选风险等级 >= 3 的断面，查询天气
                        4. 为每个高风险断面生成热力图和对比图
                        5. 综合数据生成完整的风险评估报告

                        === 回答要求 ===
                        1. 使用中文撰写，语言专业但易懂
                        2. 如果用户问的是知识类问题，基于知识库回答
                        3. 如果用户要求出报告，严格按照报告流程执行
                        4. 对专业指标进行通俗解释
                        """ + "\n\n" + skillPrompt)
                .model(deepseekModel)
                .memory(new InMemoryMemory())
                .toolkit(reportToolkit)
                .skillBox(skillBox)
                .knowledge(knowledge)
                .ragMode(RAGMode.AGENTIC)
                .retrieveConfig(RetrieveConfig.builder()
                        .limit(5)
                        .scoreThreshold(0.4)
                        .build())
                .hook(traceHook)
                .maxIters(15)
                .build();
    }
}
