package com.yangtze.bankwarning.ai.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.security.PythonImportScanner;
import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
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
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.rag.exception.VectorStoreException;
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
import java.util.LinkedHashMap;
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
            @Value("${agentscope.dashscope.api-key:}") String apiKey,
            @Value("${agentscope.dashscope.embedding-model-name:text-embedding-v3}") String modelName,
            @Value("${agentscope.dashscope.embedding-dimensions:1024}") int dimensions) {
        log.info("[config] DashScope apiKey={}, modelName={}, dimensions={}",
                apiKey != null ? apiKey.substring(0, Math.min(8, apiKey.length())) + "..." : "null",
                modelName, dimensions);
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
    public KnowledgeQueryTool knowledgeQueryTool(KnowledgeService knowledgeService) {
        return new KnowledgeQueryTool(knowledgeService);
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
            @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}") String cacheDir,
            PythonImportScanner importScanner) throws Exception {
        List<AgentSkillRepository> repos = new ArrayList<>();
        Set<String> registered = new HashSet<>();
        Path cacheBase = Paths.get(cacheDir);

        ClasspathSkillRepository classpathRepo = new ClasspathSkillRepository("skills");
        for (AgentSkill skill : classpathRepo.getAllSkills()) {
            String name = skill.getName();
            if (registered.add(name)) {
                materializeSkillScripts(skill, cacheBase, importScanner);
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
                    materializeSkillScripts(skill, cacheBase, importScanner);
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

    private void materializeSkillScripts(AgentSkill skill, Path cacheBase, PythonImportScanner scanner) {
        Map<String, String> resources = skill.getResources();
        if (resources == null || resources.isEmpty()) return;
        Path base = cacheBase.resolve(skill.getName()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
        } catch (Exception e) {
            log.error("[SkillRepo] failed to create cache dir {}: {}", base, e.getMessage());
            return;
        }
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String relPath = entry.getKey();
            String content = entry.getValue();
            if (content == null) continue;
            try {
                // 路径防逃逸：normalize 后必须位于 skill 缓存目录内，防止 ../ 覆盖外部文件
                Path target = SkillPathGuard.safeResolve(base, relPath);
                Files.createDirectories(target.getParent());
                // 资源可能以 base64 编码传递（如二进制脚本/模板），统一在此解码
                String decoded = content.startsWith("base64:")
                        ? new String(Base64.getDecoder().decode(content.substring("base64:".length())))
                        : content;
                Files.writeString(target, decoded,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                log.info("[SkillRepo] materialized: {}", target);
            } catch (Exception e) {
                log.error("[SkillRepo] failed to materialize {}: {}", relPath, e.getMessage());
            }
        }
        // 物化后对 skill 目录下所有 .py 做静态 import 扫描，危险模块需在 SKILL.md permissions 放行
        try {
            var scanResult = scanner.scanSkillDir(base, PythonImportScanner.parsePermissions(base));
            if (!scanResult.getViolations().isEmpty()) {
                log.warn("[SkillRepo] skill '{}' import violations: {}", skill.getName(),
                        String.join(", ", scanResult.getViolations()));
            }
        } catch (Exception e) {
            log.warn("[SkillRepo] scan skill '{}' failed: {}", skill.getName(), e.getMessage());
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
