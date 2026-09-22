package com.yangtze.bankwarning.ai.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.service.KnowledgeService;
import com.yangtze.bankwarning.ai.service.NacosSkillRepositoryHolder;
import com.yangtze.bankwarning.ai.service.PdfService;
import com.yangtze.bankwarning.ai.service.SkillCacheService;
import com.yangtze.bankwarning.ai.service.SkillVersionService;
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
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
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
            SkillVersionService skillVersionService) throws Exception {
        List<AgentSkillRepository> repos = new ArrayList<>();
        Set<String> registered = new HashSet<>();

        ClasspathSkillRepository classpathRepo = new ClasspathSkillRepository("skills");
        for (AgentSkill skill : classpathRepo.getAllSkills()) {
            String name = skill.getName();
            if (registered.add(name)) {
                materializeSafe(skillVersionService, skill);
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
                    materializeSafe(skillVersionService, skill);
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

    /**
     * 把 classpath（或 Nacos）里的 skill 物化到 .skills-cache/&lt;skill&gt;/&lt;version&gt;/。
     *
     * <p>关键点：框架的 {@code skill.getResources()} <b>按设计不含 SKILL.md</b>
     * （{@code SkillFileSystemHelper.isValidResource} 里明确把 SKILL.md 排除），
     * 框架是把 SKILL.md 拆成两半存放的：
     * <ul>
     *   <li>{@code getMetadata()}   —— frontmatter（name / version / permissions / output …）</li>
     *   <li>{@code getSkillContent()} —— 只有正文，不含 frontmatter</li>
     * </ul>
     *
     * <p>所以这里必须用框架自己的 {@link MarkdownSkillParser#generate} 把两半拼回完整
     * SKILL.md 再传下去（它内部对 metadata 整表 YAML dump，version/permissions 一个不丢；
     * 手工拼串只留 name + description，会让权限声明蒸发）。
     *
     * <p>若省掉这一步，版本目录里就没有 SKILL.md，会连锁导致三件事同时坏掉：
     * 版本号解析不出（退成 0.0.0）、权限元数据读不到（审批页显示无权限）、
     * {@code hasVersion()} 恒为 false（激活按钮永久失效 + 每次列技能刷 WARN）。
     */
    private void materializeSafe(SkillVersionService skillVersionService, AgentSkill skill) {
        try {
            String fullSkillMd = composeSkillMd(skill);
            skillVersionService.registerResources(skill.getName(), skill.getResources(),
                    fullSkillMd, "classpath", "system");
        } catch (Exception e) {
            log.error("[SkillRepo] 物化失败 {}: {}", skill.getName(), e.getMessage());
        }
    }

    /** 用框架正规 API 把 metadata + 正文拼回完整 SKILL.md；内容缺失时返回 null（由下层决定是否补写）。 */
    private static String composeSkillMd(AgentSkill skill) {
        String content = skill.getSkillContent();
        Map<String, Object> meta = skill.getMetadata();
        if ((content == null || content.isBlank()) && (meta == null || meta.isEmpty())) {
            return null;
        }
        return MarkdownSkillParser.generate(meta == null ? Map.of() : meta, content == null ? "" : content);
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
