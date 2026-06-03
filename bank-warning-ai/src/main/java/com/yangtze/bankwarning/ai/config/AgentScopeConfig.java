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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yangtze.bankwarning.ai.service.PdfService;
import com.yangtze.bankwarning.ai.tool.PdfTools;

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
    public SkillBox skillBox(Toolkit reportToolkit) throws Exception {
        SkillBox skillBox = new SkillBox(reportToolkit);
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            for (AgentSkill skill : repo.getAllSkills()) {
                skillBox.registration().skill(skill).apply();
                System.out.println("[SkillBox] registered: " + skill.getName());
            }
        }
        return skillBox;
    }

    @Bean
    public ReActAgent reportAgent(Model deepseekModel, Toolkit reportToolkit,
                                  SkillBox skillBox, ReasoningTraceHook traceHook) {
        String skillPrompt = skillBox.getSkillPrompt();
        return ReActAgent.builder()
                .name("ReportAgent")
                .sysPrompt("""
                        你是一名资深的水利工程专家，专门从事长江河岸崩塌风险评估工作。

                        你有以下工具可以调用，请根据任务需要自主选择使用：

                        数据查询：
                        1. query_risk_data(task_id) — 查询指定任务的断面风险评估数据（风险等级、指标结果、所属银行）

                        图表生成：
                        2. generate_risk_distribution_map(task_id) — 生成风险分布图
                        3. generate_scour_heatmap(section_id) — 生成冲淤热力图
                        4. generate_section_comparison_chart(section_id) — 生成断面对比图

                        实时天气（用于评估降雨对崩岸风险的影响）：
                        5. get_weather_forecast(lng, lat, days) — 查询指定经纬度未来 N 天天气，days 范围 1-7
                        6. get_weather_warning(lng, lat) — 查询当前生效的天气预警（暴雨、台风等）

                        工作流程（重要：必须按顺序执行全部步骤）：
                        1. 首先调用 query_risk_data(task_id) 获取任务所有断面的数据
                        2. 调用 generate_risk_distribution_map(task_id) 生成全局风险分布图（一张图包含所有断面）
                        3. 从步骤1的结果中筛选出风险等级 >= 3（中高风险及以上）的断面，并为每个断面计算经纬度中心点
                        4. 对步骤3筛出的每个高风险断面，调用 get_weather_forecast(lng, lat, 3) 查询未来3天天气，
                           必要时再调用 get_weather_warning(lng, lat) 获取预警信息
                           注意：低风险断面（等级 1-2）无需查询天气，避免不必要的 API 调用
                        5. 遍历步骤3的高风险断面，为每个 section_id 依次调用：
                           a. generate_scour_heatmap(section_id) — 断面冲淤热力图
                           b. generate_section_comparison_chart(section_id) — 断面对比图
                        6. 最后综合所有数据 + 天气信息 + 图表结果，生成完整的风险评估报告

                        报告要求：
                        1. 使用中文撰写，语言专业但易懂
                        2. 结构清晰：概述 → 指标分析 → 风险评估 → 叠加天气影响 → 建议措施
                        3. 对专业指标进行通俗解释
                        4. 给出明确的风险等级判定依据
                        5. 针对不同风险等级给出相应的应对建议

                        【关键规则：天气与崩岸风险的叠加判断】
                        当天气数据显示存在以下情况之一时，你必须在报告中显式新增"叠加天气风险"章节：
                        - 未来 24 小时内累计降水量 ≥ 25 mm
                        - 未来 72 小时内任一日降水量 ≥ 50 mm（暴雨）
                        - 当前生效暴雨/台风/大风预警

                        触发上述任一条件时，你必须：
                        1. 在"风险评估"章节增加"叠加天气风险"段落，说明具体降雨情况和预警信息
                        2. 对风险等级 ≥ 3 的断面，将建议措辞从"定期巡查"升级为"加密巡查"或"提前处置"
                        3. 若 24h 累计降水 ≥ 50 mm 或有红色/橙色预警，必须新增"应急建议"章节，
                           内容包括但不限于：加密巡查频次、提前通知附近村镇、准备应急物资等
                        """ + "\n\n" + skillPrompt)
                .model(deepseekModel)
                .memory(new InMemoryMemory())
                .toolkit(reportToolkit)
                .skillBox(skillBox)
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
