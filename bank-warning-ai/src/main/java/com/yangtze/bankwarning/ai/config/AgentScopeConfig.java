package com.yangtze.bankwarning.ai.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.service.PdfService;
import com.yangtze.bankwarning.ai.service.VisualizationService;
import com.yangtze.bankwarning.ai.service.WeatherService;
import com.yangtze.bankwarning.ai.tool.KnowledgeQueryTool;
import com.yangtze.bankwarning.ai.tool.PdfTools;
import com.yangtze.bankwarning.ai.tool.RiskDataTools;
import com.yangtze.bankwarning.ai.tool.VisualizationTools;
import com.yangtze.bankwarning.ai.tool.WeatherTools;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.PgVectorStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true);
        return mapper;
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
    public KnowledgeQueryTool knowledgeQueryTool(Knowledge knowledge) {
        return new KnowledgeQueryTool(knowledge);
    }

    @Bean
    public Toolkit reportToolkit(RiskDataTools riskDataTools,
                                 VisualizationTools visualizationTools,
                                 WeatherTools weatherTools,
                                 PdfTools pdfTools,
                                 KnowledgeQueryTool knowledgeQueryTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(riskDataTools);
        toolkit.registerTool(visualizationTools);
        toolkit.registerTool(weatherTools);
        toolkit.registerTool(pdfTools);
        toolkit.registerTool(knowledgeQueryTool);
        return toolkit;
    }

    @Bean
    public List<AgentSkillRepository> agentSkillRepositories(
            Toolkit reportToolkit,
            ObjectProvider<NacosSkillRepositoryHolder> nacosHolderProvider,
            @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}") String cacheDir) throws Exception {
        List<AgentSkillRepository> repos = new ArrayList<>();
        Set<String> registered = new HashSet<>();
        Path cacheBase = Paths.get(cacheDir);

        ClasspathSkillRepository classpathRepo = new ClasspathSkillRepository("skills");
        for (AgentSkill skill : classpathRepo.getAllSkills()) {
            String name = skill.getName();
            if (registered.add(name)) {
                materializeSkillScripts(skill, cacheBase);
                log.info("[SkillRepo] registered local: {}", name);
            } else {
                log.warn("[SkillRepo] duplicate local skill skipped: {}", name);
            }
        }
        repos.add(classpathRepo);

        NacosSkillRepositoryHolder nacosHolder = nacosHolderProvider.getIfAvailable();
        if (nacosHolder != null && nacosHolder.isAvailable()) {
            for (AgentSkill skill : nacosHolder.getRepository().getAllSkills()) {
                String name = skill.getName();
                if (registered.add(name)) {
                    materializeSkillScripts(skill, cacheBase);
                    log.info("[SkillRepo] registered nacos: {}", name);
                } else {
                    log.info("[SkillRepo] nacos skill '{}' skipped (local has higher priority)", name);
                }
            }
            repos.add(nacosHolder.getRepository());
        } else {
            log.info("[SkillRepo] Nacos not configured or unreachable, using local skills only");
        }
        return repos;
    }

    private void materializeSkillScripts(AgentSkill skill, Path cacheBase) {
        Map<String, String> resources = skill.getResources();
        if (resources == null || resources.isEmpty()) return;
        Path base = cacheBase.resolve(skill.getName());
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String relPath = entry.getKey();
            String content = entry.getValue();
            if (content == null) continue;
            Path target = base.resolve(relPath);
            try {
                Files.createDirectories(target.getParent());
                String decoded = content.startsWith("base64:")
                        ? new String(Base64.getDecoder().decode(content.substring("base64:".length())))
                        : content;
                Files.writeString(target, decoded,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                log.info("[SkillRepo] materialized: {}", target);
            } catch (Exception e) {
                log.error("[SkillRepo] failed to materialize {}: {}", target, e.getMessage());
            }
        }
    }

    @Bean
    @Qualifier("chatAgent")
    public HarnessAgent chatAgent(Model deepseekModel, Toolkit reportToolkit,
                                  List<AgentSkillRepository> agentSkillRepositories,
                                  @Qualifier("chatTraceMiddleware") ReasoningTraceMiddleware chatTraceMiddleware) {
        Path wsPath = Path.of(".agentscope", "workspace", "chat").toAbsolutePath();
        return HarnessAgent.builder()
                .name("ChatAgent")
                .agentId("chat-agent")
                .model(deepseekModel)
                .workspace(wsPath)
                .abstractFilesystem(new LocalFilesystem(wsPath))
                .toolkit(reportToolkit)
                .skillRepositories(agentSkillRepositories)
                .middleware(chatTraceMiddleware)
                .maxIters(15)
                .memory(MemoryConfig.builder()
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(java.time.Duration.ofMinutes(5)))
                        .sessionRetentionDays(36500)
                        .build())
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .build();
    }
}
