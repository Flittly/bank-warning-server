package com.yangtze.bankwarning.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.service.MarkdownReportService;
import com.yangtze.bankwarning.ai.service.PartitionSectionCsvService;
import com.yangtze.bankwarning.ai.service.VisualizationService;
import com.yangtze.bankwarning.ai.store.ReportRunStore;
import com.yangtze.bankwarning.ai.tool.BoundRiskDistributionTool;
import com.yangtze.bankwarning.ai.tool.RiskDataTools;
import com.yangtze.bankwarning.ai.tool.VisualizationTools;
import com.yangtze.bankwarning.ai.tool.WeatherTools;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告生成工作流服务。
 *
 * 设计要点：
 * 1. 6 步硬编码工作流（按钮触发的报告生成是确定性流程，不靠 LLM 自由发挥）
 * 2. 每步独立 mini agent：专属 systemPrompt + 专属 toolkit（仅注册该步需要的工具）
 * 3. executionContext 在 6 步之间共享（替代 LLM memory 跨步传递）
 * 4. 持久化 checkpoint：每步状态与中间上下文落库（ai_report_runs），
 *    服务重启/步骤失败后，state == DONE 的子任务自动跳过，从断点续跑
 * 5. 进度可观察：PlanProgress DTO 从数据库读取，暴露给前端
 * 6. 同一 task 同一时刻只允许一个进行中的工作流（数据库部分唯一索引兜底）
 *
 * 端点契约：
 * - POST /v0/bank/ai/agent/report/task/{taskId}  → executeTaskReport(taskId)
 * - GET  /v0/bank/ai/agent/report/progress?taskId=xxx → getProgress(taskId)
 */
@Service
public class ReportWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ReportWorkflowService.class);

    /**
     * 出图工具回传的分区明细行前缀（见 {@code VisualizationTools.partitionsJson}）。
     * 报告分区边界只认这一行——即本次出图**实际生效**的 bbox，而不是 Java 侧另算一套。
     */
    static final String PARTITION_MARKER = "分区明细(JSON)：";

    /** {@code query_risk_data} 输出的坐标段，格式见 {@code RiskDataTools.coordText}。 */
    private static final Pattern COORD_PATTERN =
            Pattern.compile("坐标：\\(([-\\d.]+),\\s*([-\\d.]+)\\)");
    private static final Pattern LEVEL_PATTERN = Pattern.compile("风险等级：(\\d+)");
    private static final Pattern SECTION_NAME_PATTERN = Pattern.compile("断面：([^（|]+)");

    /**
     * {@code query_risk_data} 输出的断面 ID 段，格式见 {@code RiskDataTools.sectionIdText}。
     *
     * <p>必须是**真 ID**（{@code sec-task-...-5}）：Step 5 要拿它去 {@code section_profiles}
     * 里按 {@code section_id} 取剖面。用断面名代替时一条也查不到（两者在库里零重合）。
     */
    private static final Pattern SECTION_ID_PATTERN = Pattern.compile("ID：([^|]+)");

    private static final StepInfo[] STEPS = {
            new StepInfo("query_data", "查询任务所有断面风险评估数据",
                    "返回所有 section 的 risk_level、指标、所属银行"),
            new StepInfo("gen_distribution", "生成任务全局风险分布图",
                    "返回 risk_distribution_map 图片路径"),
            new StepInfo("filter_high_risk", "从 Step 1 结果中筛选 risk_level >= 3 的断面",
                    "返回高风险断面列表（含名称、坐标）"),
            new StepInfo("query_weather", "对每个高风险断面查天气",
                    "返回高风险断面对应的天气 JSONs"),
            new StepInfo("gen_section_charts", "对每个高风险断面生成热力图和对比图",
                    "返回各断面的图表 URL 列表"),
            new StepInfo("synthesize", "综合全部数据撰写最终风险评估报告",
                    "完整中文报告文本（按 6 段结构）")
    };

    private final Model model;
    private final RiskDataTools riskDataTools;
    private final VisualizationTools visualizationTools;
    private final VisualizationService visualizationService;
    private final WeatherTools weatherTools;
    private final List<AgentSkillRepository> skillRepositories;
    private final ReasoningTraceMiddleware traceMiddleware;
    private final MarkdownReportService markdownReportService;
    private final PartitionSectionCsvService partitionSectionCsvService;
    private final ReportRunStore reportRunStore;
    private final ObjectMapper json = new ObjectMapper();

    public ReportWorkflowService(Model model,
                                 RiskDataTools riskDataTools,
                                 VisualizationTools visualizationTools,
                                 VisualizationService visualizationService,
                                 WeatherTools weatherTools,
                                 List<AgentSkillRepository> skillRepositories,
                                 @Qualifier("reportTraceMiddleware") ReasoningTraceMiddleware traceMiddleware,
                                 MarkdownReportService markdownReportService,
                                 PartitionSectionCsvService partitionSectionCsvService,
                                 ReportRunStore reportRunStore) {
        this.model = model;
        this.riskDataTools = riskDataTools;
        this.visualizationTools = visualizationTools;
        this.visualizationService = visualizationService;
        this.weatherTools = weatherTools;
        this.skillRepositories = skillRepositories;
        this.traceMiddleware = traceMiddleware;
        this.markdownReportService = markdownReportService;
        this.partitionSectionCsvService = partitionSectionCsvService;
        this.reportRunStore = reportRunStore;
    }

    /**
     * 启动时把遗留 RUNNING 工作流标记为 FAILED（单实例假设）：
     * 进程已重启，进程内不可能还有存活的工作流，下次触发时自动续跑。
     * 多实例部署需要租约/心跳式回收，属于后续阶段。
     */
    @PostConstruct
    public void recoverInterruptedRuns() {
        int count = reportRunStore.markStaleRunsFailed("服务重启，任务中断（重新触发即可续跑）");
        if (count > 0) {
            log.info("[workflow] 将 {} 个遗留 RUNNING 工作流标记为 FAILED", count);
        }
    }

    public String executeTaskReport(String taskId) {
        return executeTaskReport(taskId, null, null, null);
    }

    /**
     * 生成报告（支持按经纬度分幅）。
     *
     * <p>分幅语义与出图**完全一致**——校验、归一化都复用
     * {@link VisualizationService#validateSplitConfig}：
     * <ul>
     *   <li>{@code splitRows/splitCols} 为行（纬度方向）× 列（经度方向）分块数，1 = 该方向不分；</li>
     *   <li>{@code splitTiles} 为逐幅自定义边界的 JSON 数组，缺省的边沿用均分网格；</li>
     *   <li>三段全空 = 不分幅，报告结构与历史版本一致。</li>
     * </ul>
     *
     * <p>参数非法时**在起工作流之前**就抛 {@link IllegalArgumentException}，不会白跑 Step 1。
     */
    public String executeTaskReport(String taskId, Integer splitRows, Integer splitCols,
                                    String splitTiles) {
        log.info("[workflow] start report generation, taskId={}, split={}x{}",
                taskId, splitRows, splitCols);

        Map<String, Object> splitConfig;
        try {
            splitConfig = visualizationService.validateSplitConfig(splitRows, splitCols, splitTiles);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分幅参数非法：" + e.getMessage(), e);
        }

        Long userId = SecurityUtils.getCurrentUserId();
        Long dataFilter = SecurityUtils.getCurrentUserIdForDataFilter();

        ReportRunStore.ReportRun existing =
                reportRunStore.findLatestByTaskId(taskId, dataFilter).orElse(null);
        if (existing != null && isActive(existing.status())) {
            throw new IllegalStateException("任务正在生成报告中，请刷新进度查看，不要重复触发");
        }

        boolean resuming = existing != null && ReportRunStore.STATUS_FAILED.equals(existing.status());
        WorkflowState state = resuming
                ? WorkflowState.fromJson(json, existing.stepStatesJson(), existing.contextJson())
                : new WorkflowState();
        String runId = resuming ? existing.runId() : UUID.randomUUID().toString();

        // 分幅参数写进 context（随 checkpoint 落库）：
        // - 续跑时不带参数也仍能沿用首次触发时的分幅，保证 Step 2 的图与报告分区不换口径；
        // - 带参数时以本次为准（显式覆盖）。
        if (splitConfig != null) {
            state.context.put("split", splitConfig);
            state.context.put("splitTilesJson", splitTiles);
            log.info("[workflow] 分幅出图 rows={} cols={} customTiles={}",
                    splitConfig.get("rows"), splitConfig.get("cols"),
                    splitConfig.getOrDefault("tiles", "无"));
        }

        if (resuming) {
            if (!reportRunStore.claimRunning(runId, dataFilter)) {
                throw new IllegalStateException("任务状态已变化（可能已在生成中），请刷新后重试");
            }
            log.info("[workflow] resume interrupted run, runId={}, taskId={}", runId, taskId);
        } else {
            try {
                reportRunStore.create(runId, taskId, userId,
                        toStepStatesJson(state), toContextJson(state));
            } catch (DuplicateKeyException e) {
                throw new IllegalStateException("任务正在生成报告中，请刷新进度查看，不要重复触发", e);
            }
            if (!reportRunStore.claimRunning(runId, dataFilter)) {
                throw new IllegalStateException("任务正在生成报告中，请刷新进度查看，不要重复触发");
            }
        }

        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String reportFileName = "report_" + taskId + "_" + ts + ".md";
        String taskDir = System.getProperty("user.dir") + "/visualization/output/report_"
                + taskId + "_" + ts;
        new java.io.File(taskDir).mkdirs();
        log.info("[workflow] task output dir: {}", taskDir);
        VisualizationService.beginTask(taskDir);

        try {
            for (int i = 0; i < STEPS.length; i++) {
                if (state.stepStates[i] == StepState.DONE) {
                    log.info("[workflow] [{}] skip (already DONE)", STEPS[i].name);
                    continue;
                }

                log.info("[workflow] [{}] starting", STEPS[i].name);
                state.stepStates[i] = StepState.IN_PROGRESS;
                persistProgress(runId, dataFilter, i, state);
                int traceBefore = traceMiddleware.getLog().size();

                try {
                    String result = executeOneStep(i, taskId, state);
                    state.stepStates[i] = StepState.DONE;
                    appendChartsFromTrace(state, i, traceBefore);
                    persistProgress(runId, dataFilter, i, state);
                    log.info("[workflow] [{}] DONE", STEPS[i].name);
                } catch (Exception e) {
                    log.error("[workflow] [{}] FAILED: {}", STEPS[i].name, e.getMessage(), e);
                    state.stepStates[i] = StepState.FAILED;
                    state.lastError = e.getMessage();
                    persistProgress(runId, dataFilter, i, state);
                    reportRunStore.markFailed(runId, dataFilter, e.getMessage());
                    throw new RuntimeException("Step " + STEPS[i].name + " failed: " + e.getMessage(), e);
                }
            }

            String finalReport = (String) state.context.getOrDefault("finalReport", "");
            log.info("[workflow] finished, total length={}", finalReport.length());

            // 分区断面详情：报告按经纬度分成 N 个区时，同时落 N 个独立 CSV，
            // 与报告（及子图）同目录，导出报告时一并打包下载。
            // 失败不影响报告本身（服务内部已吞异常并返回空结果）。
            PartitionSectionCsvService.CsvExport sectionCsvs = writeSectionCsvs(taskId, taskDir, state);

            String mdContent = markdownReportService.buildMarkdownContent(
                    taskId, finalReport, collectCharts(state), sectionCsvs);

            String savedFileName = reportFileName;
            try {
                String mdPath = markdownReportService.generateMarkdownReport(
                        taskId, finalReport, collectCharts(state), sectionCsvs);
                savedFileName = new java.io.File(mdPath).getName();
                log.info("[workflow] markdown report saved: {}", mdPath);
            } catch (Exception e) {
                log.error("[workflow] failed to save markdown report: {}", e.getMessage(), e);
            }

            reportRunStore.markFinished(runId, dataFilter, savedFileName);
            return mdContent;
        } finally {
            VisualizationService.endTask();
        }
    }

    public String getReportFileName(String taskId) {
        Long dataFilter = SecurityUtils.getCurrentUserIdForDataFilter();
        return reportRunStore.findLatestByTaskId(taskId, dataFilter)
                .filter(run -> ReportRunStore.STATUS_FINISHED.equals(run.status()))
                .map(ReportRunStore.ReportRun::reportFileName)
                .orElse(null);
    }

    public PlanProgress getProgress(String taskId) {
        Long dataFilter = SecurityUtils.getCurrentUserIdForDataFilter();
        return reportRunStore.findLatestByTaskId(taskId, dataFilter)
                .map(this::toPlanProgress)
                .orElse(null);
    }

    private PlanProgress toPlanProgress(ReportRunStore.ReportRun run) {
        List<PlanProgress.SubTaskProgress> subProgress = new ArrayList<>();
        int completed = 0;
        List<String> states = parseStepStates(run.stepStatesJson());
        for (int i = 0; i < STEPS.length; i++) {
            StepState ss = i < states.size() ? StepState.valueOf(states.get(i)) : StepState.TODO;
            if (ss == StepState.DONE) {
                completed++;
            }
            subProgress.add(new PlanProgress.SubTaskProgress(
                    i, STEPS[i].name, STEPS[i].description, ss.name(),
                    STEPS[i].expectedOutcome, ""));
        }

        String overallStatus = switch (run.status()) {
            case ReportRunStore.STATUS_FINISHED -> "FINISHED";
            case ReportRunStore.STATUS_FAILED -> "FAILED";
            case ReportRunStore.STATUS_PENDING -> "PENDING";
            default -> "RUNNING";
        };

        return new PlanProgress("report-" + run.taskId(), run.taskId(), overallStatus,
                STEPS.length, completed, subProgress);
    }

    private List<String> parseStepStates(String stepStatesJson) {
        if (stepStatesJson == null || stepStatesJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return json.readValue(stepStatesJson, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("[workflow] 解析步骤状态失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ========== 持久化 ==========

    private void persistProgress(String runId, Long dataFilter, int currentStep, WorkflowState state) {
        reportRunStore.updateProgress(runId, dataFilter, currentStep,
                toStepStatesJson(state), toContextJson(state));
    }

    private String toStepStatesJson(WorkflowState state) {
        try {
            List<String> names = new ArrayList<>();
            for (StepState step : state.stepStates) {
                names.add(step.name());
            }
            return json.writeValueAsString(names);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化步骤状态失败", e);
        }
    }

    private String toContextJson(WorkflowState state) {
        try {
            return json.writeValueAsString(state.context);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化工作流上下文失败", e);
        }
    }

    private static boolean isActive(String status) {
        return ReportRunStore.STATUS_PENDING.equals(status)
                || ReportRunStore.STATUS_RUNNING.equals(status);
    }

    // ========== 单步执行 ==========

    private String executeOneStep(int idx, String taskId, WorkflowState state) {
        return switch (idx) {
            case 0 -> step1QueryData(taskId, state);
            case 1 -> step2GenDistribution(taskId, state);
            case 2 -> step3FilterHighRisk(state);
            case 3 -> step4QueryWeather(state);
            case 4 -> step5GenSectionCharts(taskId, state);
            case 5 -> step6Synthesize(taskId, state);
            default -> throw new IllegalStateException("Unknown step index: " + idx);
        };
    }

    private String step1QueryData(String taskId, WorkflowState state) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(riskDataTools);

        ReActAgent agent = buildMiniAgent("step1_query_data", toolkit, """
                你正在执行 Step 1：查询任务的所有断面风险数据。
                必须且只能调用工具 query_risk_data，参数 taskId 为：%s。
                返回工具的原始输出，不要做任何加工。
                """.formatted(taskId));

        Msg out = agent.call(List.of(new UserMessage("user", "开始查询任务数据"))).block();
        String result = out.getTextContent();
        state.context.put("riskDataText", result);
        return "已查询任务 " + taskId + " 的断面风险数据";
    }

    private String step2GenDistribution(String taskId, WorkflowState state) {
        Toolkit toolkit = new Toolkit();
        Map<String, Object> split = splitConfigOf(state);
        if (split == null) {
            toolkit.registerTool(visualizationTools);
            toolkit.removeTool("generate_scour_heatmap");
            toolkit.removeTool("generate_section_comparison_chart");
        } else {
            // 分幅参数**闭包进工具**而不是写进提示词让模型转述：
            // 模型漏传/改坏 JSON 会让出图分区与报告分区对不上，而且不报错。
            // 绑定版工具对模型只暴露 task_id，其余参数由 Java 侧固定。
            toolkit.registerTool(new BoundRiskDistributionTool(
                    visualizationService,
                    intOf(split.get("rows")), intOf(split.get("cols")),
                    (String) state.context.get("splitTilesJson")));
        }

        ReActAgent agent = buildMiniAgent("step2_gen_distribution", toolkit, """
                你正在执行 Step 2：生成任务的风险分布图。
                必须且只能调用工具 generate_risk_distribution_map，参数 taskId 为：%s。
                返回工具的原始输出（必须原样返回，尤其是其中的「分区明细(JSON)」一行，不要精简）。
                """.formatted(taskId));

        Msg out = agent.call(List.of(new UserMessage("user", "开始生成风险分布图"))).block();
        String result = out.getTextContent();
        state.context.put("distributionMapText", result);
        // 兜底捕获：模型若没原样回传，appendChartsFromTrace 还会从中间件的工具结果里再取一次
        capturePartitions(state, result);
        return result;
    }

    private String step3FilterHighRisk(WorkflowState state) {
        String riskDataText = (String) state.context.get("riskDataText");
        if (riskDataText == null) {
            throw new IllegalStateException("Step 1 未执行，无法筛选");
        }
        List<HighRiskSection> highRisk = new ArrayList<>();
        for (RiskRow row : parseRiskRows(riskDataText)) {
            // 坐标一并带出：Step 4 的天气查询按 (lng, lat) 调外部接口，
            // 以及 Step 6 的经纬度分区归属，都需要它。
            if (row.level() >= 3) {
                highRisk.add(new HighRiskSection(row.name(), row.sectionId(), row.lng(), row.lat(), row.level()));
            }
        }
        // 序列化友好的 Map 形式落库（断点续跑时按 Map 读回）
        List<Map<String, Object>> serializable = new ArrayList<>();
        for (HighRiskSection section : highRisk) {
            serializable.add(toHighRiskMap(section));
        }
        state.context.put("highRiskSections", serializable);
        long withCoord = highRisk.stream().filter(s -> s.lng() != null && s.lat() != null).count();
        if (withCoord < highRisk.size()) {
            log.warn("[workflow] {}/{} 个高风险断面缺坐标（cross_sections.geom 为空？），"
                            + "这些断面无法归入经纬度分区、也无法查天气",
                    highRisk.size() - withCoord, highRisk.size());
        }
        return "筛选出 " + highRisk.size() + " 个高风险断面（风险等级 >= 3）";
    }

    private String step4QueryWeather(WorkflowState state) {
        List<Map<String, Object>> highRisk = readHighRiskSections(state);
        if (highRisk.isEmpty()) {
            state.context.put("weatherResults", new LinkedHashMap<String, String>());
            return "无高风险断面，跳过天气查询";
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTools);

        StringBuilder ctx = new StringBuilder("需要查询天气的高风险断面列表：\n");
        for (int i = 0; i < highRisk.size(); i++) {
            Map<String, Object> s = highRisk.get(i);
            ctx.append(String.format("%d. 断面：%s | 风险等级：%d | 坐标：(%s, %s)\n",
                    i + 1, s.get("name"), levelOf(s), s.get("lng"), s.get("lat")));
        }
        ctx.append("\n对每个断面调用 get_weather_forecast(lng, lat, 3) 获取未来 3 天天气。\n");
        ctx.append("如果数据显示有暴雨/台风预警，再额外调用 get_weather_warning(lng, lat)。");

        ReActAgent agent = buildMiniAgent("step4_query_weather", toolkit,
                """
                        你正在执行 Step 4：查询高风险断面的天气信息。
                        输入参数：%s

                        严格规则：
                        1. 对每个高风险断面，调用 get_weather_forecast(lng, lat, 3) 获取未来 3 天天气
                        2. 仅当预报显示暴雨/台风时，额外调用 get_weather_warning(lng, lat)
                        3. 不要做任何其他工具调用
                        4. 把所有结果整理成 JSON 格式：{"断面名": "天气摘要", ...}
                        """.formatted(ctx));

        Msg out = agent.call(List.of(new UserMessage("user", "开始批量查询天气"))).block();
        String result = out.getTextContent();
        state.context.put("weatherResultsText", result);
        return "已查询 " + highRisk.size() + " 个高风险断面的天气";
    }

    private String step5GenSectionCharts(String taskId, WorkflowState state) {
        List<Map<String, Object>> highRisk = readHighRiskSections(state);
        if (highRisk.isEmpty()) {
            state.context.put("sectionChartsText", "无高风险断面，跳过断面图生成");
            return "无高风险断面，跳过";
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(visualizationTools);
        toolkit.removeTool("generate_risk_distribution_map");

        StringBuilder sectionList = new StringBuilder();
        int unresolved = 0;
        int withChart = 0;
        for (Map<String, Object> s : highRisk) {
            String sectionId = resolveSectionId(taskId, s);
            if (sectionId == null) {
                unresolved++;
                // 不明说「跳过」的话，模型会拿断面名去调工具，然后拿到「没有剖面数据」
                // 还照样写进结论——这正是之前报告缺图的假象来源。
                sectionList.append("- 断面：").append(s.get("name"))
                        .append(" | section_id：未解析（本次跳过，不要调用出图工具）\n");
                continue;
            }
            withChart++;
            sectionList.append("- 断面：").append(s.get("name"))
                    .append(" | section_id：").append(sectionId).append('\n');
        }
        if (unresolved > 0) {
            log.warn("[workflow] {}/{} 个高风险断面解析不出 section_id（cross_sections 与 "
                            + "section_profiles 均无匹配），Step 5 跳过这些断面",
                    unresolved, highRisk.size());
        }

        ReActAgent agent = buildMiniAgent("step5_gen_charts", toolkit,
                """
                        你正在执行 Step 5：对每个高风险断面生成冲淤热力图和断面对比图。
                        需要处理的断面列表：%s

                        严格规则：
                        1. 对每个断面依次调用 generate_scour_heatmap(section_id)
                        2. 然后调用 generate_section_comparison_chart(section_id)
                        3. section_id **必须原样使用上面给出的那一串**（形如 sec-task-...-5），
                           绝对不要拿断面名（如「断面6」）代替——库里两者是两套值，用名字一条也查不到
                        4. 标了「未解析」的断面直接跳过，不要编造 section_id
                        5. 不要调用 generate_risk_distribution_map（那是 Step 2 干的）
                        6. 把所有图片路径整理成 JSON 返回；失败的断面在 JSON 里如实标注失败原因
                        """.formatted(sectionList));

        Msg out = agent.call(List.of(new UserMessage("user", "开始生成断面图"))).block();
        String result = out.getTextContent();
        state.context.put("sectionChartsText", result);
        return "已为 " + withChart + " 个高风险断面发起出图（" + unresolved + " 个因解析不出 section_id 跳过）";
    }

    /**
     * 取该断面真正的 {@code section_id}。
     *
     * <p>优先用 Step 1 文本里带回来的 ID（正常路径，零额外查询）；老文本 / 模型丢字段时
     * 按 {@code (task_id, section_name)} 回查一次，避免整批断面图静默消失。
     */
    private String resolveSectionId(String taskId, Map<String, Object> section) {
        Object id = section.get("section_id");
        if (id != null) {
            String text = String.valueOf(id).trim();
            if (!text.isEmpty() && !"未知".equals(text)) {
                return text;
            }
        }
        Object name = section.get("name");
        String sectionName = name == null ? null : String.valueOf(name).trim();
        // 注意：这里的 section 是 readHighRiskSections 返回的副本，回填不会写回 state.context，
        // 续跑重跑 Step 5 时会再查一次（单条一次索引查询，代价可接受）。
        return visualizationService.resolveSectionId(taskId, sectionName);
    }

    private String step6Synthesize(String taskId, WorkflowState state) {
        Toolkit toolkit = new Toolkit();

        String riskData = (String) state.context.getOrDefault("riskDataText", "");
        String dist = (String) state.context.getOrDefault("distributionMapText", "");
        String weather = (String) state.context.getOrDefault("weatherResultsText", "");
        String charts = (String) state.context.getOrDefault("sectionChartsText", "");
        // 确定性预处理：按出图回传的分区边界把断面归区并汇总（不经 LLM）
        String partitionSummary = buildPartitionSummary(state);
        boolean partitioned = !partitionSummary.isBlank();
        String partitionBlock = partitioned
                ? partitionSummary
                : "本次未按经纬度分幅（未提供分幅参数）。请按原结构撰写，不要虚构分区。";

        String fullPrompt = """
                你正在执行 Step 6：综合所有数据，撰写最终风险评估报告。

                === 任务信息 ===
                任务 ID：%s

                === Step 1：断面风险数据 ===
                %s

                === Step 2：风险分布图 ===
                %s

                === Step 4：天气数据 ===
                %s

                === Step 5：断面图（热力图+对比图） ===
                %s

                === 按经纬度分区汇总（分区边界与 Step 2 出图完全一致） ===
                %s

                === 输出要求 ===
                1. 用中文撰写完整报告
                2. 结构：概述 → 指标分析 → 风险评估 → 叠加天气影响 → 建议措施
                3. 对专业指标做通俗解释
                4. 给出明确的风险等级判定依据
                5. 若天气数据显示未来 24h 累计降水 ≥ 25mm 或 72h 内任一日 ≥ 50mm 或有暴雨/台风/大风预警，必须新增"叠加天气风险"章节
                6. 若 24h 降水 ≥ 50mm 或有红色/橙色预警，必须新增"应急建议"章节
                7. 输出报告正文，不要加任何"以下是报告"之类的开场白
                8. **若上面给出了分区汇总**：正文必须按分区组织——每个分区一个二级章节，标题写明
                   「分区 N（第 r 行第 c 列，经度 x~y，纬度 u~v）」，在该章节内汇总**该分区自己的**
                   断面数量、风险等级分布、高风险断面清单与天气影响，并指向附录中该分区对应的地图；
                   分区章节之后，再写一个「跨分区总体研判」章节做横向对比（哪个分区风险最高、为什么）。
                   分区的经纬度范围与数量**只能引用上面给出的数字**，不要自行划分或改写。
                   若只有 1 个分区，可略过分区章节，按原结构撰写。
                """.formatted(taskId, riskData, dist, weather, charts, partitionBlock);

        ReActAgent agent = buildMiniAgent("step6_synthesize", toolkit, fullPrompt);

        Msg out = agent.call(List.of(new UserMessage("user", "请开始撰写最终报告"))).block();
        String report = out.getTextContent();
        state.context.put("finalReport", report);
        return report;
    }

    // ========== 经纬度分区（与出图同源） ==========

    /**
     * 本次运行的分幅配置（{@code {rows, cols, tiles?}}）；null = 不分幅。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> splitConfigOf(WorkflowState state) {
        Object raw = state.context.get("split");
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * 捕获出图工具回传的**实际生效**分区边界，落 context。
     *
     * <p>只解析 {@code VisualizationTools.partitionsJson} 自己产出的标记行——
     * 那是 Java 侧生成的确定性文本，不是 LLM 的转述。解析不到就保持原值不动
     * （不分幅时本来就没有这一行，报告按原结构写）。
     */
    void capturePartitions(WorkflowState state, String toolResultText) {
        List<Map<String, Object>> partitions = partitionsFrom(toolResultText);
        if (partitions.isEmpty()) {
            return;
        }
        state.context.put("partitions", partitions);
        log.info("[workflow] 捕获 {} 个出图分区边界（报告分区将沿用同一组数字）", partitions.size());
    }

    /**
     * 解析「分区明细(JSON)：[...]」这一行。
     *
     * <p>行由 {@code VisualizationTools.partitionsJson} 生成，字段固定：
     * row/col/west/south/east/north/sections/file_name。
     */
    List<Map<String, Object>> partitionsFrom(String text) {
        if (text == null) {
            return List.of();
        }
        int marker = text.indexOf(PARTITION_MARKER);
        if (marker < 0) {
            return List.of();
        }
        int from = marker + PARTITION_MARKER.length();
        int lineEnd = text.indexOf('\n', from);
        String payload = (lineEnd < 0 ? text.substring(from) : text.substring(from, lineEnd)).trim();
        if (payload.isEmpty() || "[]".equals(payload)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = json.readValue(
                    payload, new TypeReference<List<Map<String, Object>>>() { });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("[workflow] 分区明细解析失败，本次按不分幅处理: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<Map<String, Object>> readPartitions(WorkflowState state) {
        Object raw = state.context.get("partitions");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                out.add(copy);
            }
        }
        return out;
    }

    /**
     * 断面是否落在该分区里。
     *
     * <p>**含边判定**（west ≤ lng ≤ east 且 south ≤ lat ≤ north），与 Python
     * {@code generate_risk_map_tiles} 统计 {@code sections_in_tile} 的口径完全一致。
     * 自定义分幅出现重叠时，一个断面会同时计进多个分区——报告也照此处理，
     * 否则「分区断面数」与图上的数字会对不上。
     */
    static boolean partitionContains(Map<String, Object> partition, double lng, double lat) {
        return PartitionBounds.contains(partition, lng, lat);
    }

    /** 分区标题：「第 r 行第 c 列（经度 a~b，纬度 c~d）」。报告正文、附录、CSV 共用同一份文案。 */
    static String partitionLabel(Map<String, Object> partition) {
        return PartitionBounds.label(partition);
    }

    static String partitionKey(Map<String, Object> partition) {
        return PartitionBounds.key(partition);
    }

    /**
     * Step 1 文本里的一条断面记录；坐标可能缺失（cross_sections 缺行）。
     *
     * <p>{@code sectionId} 是**真 ID**，用于 Step 5 取剖面。老文本（没有 ID 段）解析出来是
     * {@code null}，此时 Step 5 会按 {@code (task_id, section_name)} 回查一次再决定。
     */
    record RiskRow(String name, String sectionId, int level, Double lng, Double lat) {}

    /**
     * 解析 Step 1 的断面行。坐标缺失**不**丢弃该行——它仍要进高风险清单与断面图，
     * 只是无法归入经纬度分区（会在汇总里单列为「未落入任何分区」）。
     */
    List<RiskRow> parseRiskRows(String riskDataText) {
        List<RiskRow> rows = new ArrayList<>();
        if (riskDataText == null) {
            return rows;
        }
        for (String line : riskDataText.split("\n")) {
            if (!line.contains("风险等级")) {
                continue;
            }
            Matcher levelMatcher = LEVEL_PATTERN.matcher(line);
            if (!levelMatcher.find()) {
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(levelMatcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            Matcher nameMatcher = SECTION_NAME_PATTERN.matcher(line);
            String name = nameMatcher.find() ? nameMatcher.group(1).trim() : "未知";
            Double lng = null;
            Double lat = null;
            Matcher coordMatcher = COORD_PATTERN.matcher(line);
            if (coordMatcher.find()) {
                try {
                    lng = Double.valueOf(coordMatcher.group(1));
                    lat = Double.valueOf(coordMatcher.group(2));
                } catch (NumberFormatException ignored) {
                    lng = null;
                    lat = null;
                }
            }
            rows.add(new RiskRow(name, parseSectionId(line), level, lng, lat));
        }
        return rows;
    }

    /** 从断面行里取真正的 {@code section_id}；缺失或为「未知」时返回 null。 */
    static String parseSectionId(String line) {
        Matcher matcher = SECTION_ID_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String text = matcher.group(1).trim();
        return text.isEmpty() || "未知".equals(text) ? null : text;
    }

    /** Step 4 的天气结果是 LLM 输出的 {@code {"断面名": "天气摘要"}}；解析失败就跳过归并。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseWeatherBySection(String weatherText) {
        if (weatherText == null || weatherText.isBlank()) {
            return Map.of();
        }
        int start = weatherText.indexOf('{');
        int end = weatherText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(
                    weatherText.substring(start, end + 1), Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (value != null) {
                    out.put(String.valueOf(key), String.valueOf(value));
                }
            });
            return out;
        } catch (Exception e) {
            log.debug("[workflow] 天气结果不是合法 JSON，分区内不做天气归并: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String riskLabel(int level) {
        return switch (level) {
            case 4 -> "极高风险";
            case 3 -> "高风险";
            case 2 -> "一般风险";
            case 1 -> "低风险";
            default -> "未知";
        };
    }

    /**
     * 按经纬度分区汇总断面结果——**确定性计算，不经 LLM**。
     *
     * <p>分区边界取自出图工具回传的 tiles[].bbox（本次出图实际生效的那组数），
     * 而不是在 Java 侧按公式另算一遍：只要两边都源自同一次 Python 调用，
     * 「报告分区 = 出图分区」就是构造性成立的，不依赖公式复刻得对不对。
     *
     * @return 直接喂给 Step 6 的汇总文本；不分幅 / 无数据时返回空串
     */
    String buildPartitionSummary(WorkflowState state) {
        List<Map<String, Object>> partitions = readPartitions(state);
        List<RiskRow> rows = parseRiskRows((String) state.context.get("riskDataText"));
        if (partitions.isEmpty() || rows.isEmpty()) {
            state.context.put("partitionSummary", "");
            return "";
        }

        List<List<RiskRow>> buckets = new ArrayList<>();
        for (int i = 0; i < partitions.size(); i++) {
            buckets.add(new ArrayList<>());
        }
        List<RiskRow> unassigned = new ArrayList<>();
        for (RiskRow row : rows) {
            boolean hit = false;
            if (row.lng() != null && row.lat() != null) {
                for (int i = 0; i < partitions.size(); i++) {
                    if (partitionContains(partitions.get(i), row.lng(), row.lat())) {
                        buckets.get(i).add(row);
                        hit = true;
                    }
                }
            }
            if (!hit) {
                unassigned.add(row);
            }
        }

        Map<String, String> weatherBySection = parseWeatherBySection(
                (String) state.context.getOrDefault("weatherResultsText", ""));

        StringBuilder sb = new StringBuilder();
        sb.append("分区总数：").append(partitions.size())
                .append("；断面总数：").append(rows.size())
                .append("（按断面起点坐标归属，含边判定）\n");

        for (int i = 0; i < partitions.size(); i++) {
            Map<String, Object> partition = partitions.get(i);
            List<RiskRow> bucket = buckets.get(i);
            int[] byLevel = new int[5];     // 下标 1..4 对应等级，0 装「等级未知」
            int maxLevel = 0;
            StringBuilder highRiskNames = new StringBuilder();
            for (RiskRow row : bucket) {
                int idx = (row.level() >= 1 && row.level() <= 4) ? row.level() : 0;
                byLevel[idx]++;
                maxLevel = Math.max(maxLevel, row.level());
                if (row.level() >= 3) {
                    if (highRiskNames.length() > 0) {
                        highRiskNames.append('、');
                    }
                    highRiskNames.append(row.name()).append('(').append(row.level()).append(')');
                    String weather = weatherBySection.get(row.name());
                    if (weather != null && !weather.isBlank()) {
                        highRiskNames.append('[').append(weather.replace('\n', ' ')).append(']');
                    }
                }
            }
            sb.append("- 分区 ").append(i + 1)
                    .append("（").append(partitionLabel(partition)).append("）：")
                    .append("断面 ").append(bucket.size()).append(" 条")
                    .append("｜等级分布：极高风险 ").append(byLevel[4])
                    .append("、高风险 ").append(byLevel[3])
                    .append("、一般风险 ").append(byLevel[2])
                    .append("、低风险 ").append(byLevel[1]);
            if (byLevel[0] > 0) {
                sb.append("、等级未知 ").append(byLevel[0]);
            }
            sb.append("｜最高等级：").append(maxLevel == 0 ? "未知" : riskLabel(maxLevel))
                    .append("｜高风险断面（等级≥3）：")
                    .append(highRiskNames.length() == 0 ? "无" : highRiskNames);
            Object fileName = partition.get("file_name");
            if (fileName != null && !String.valueOf(fileName).isBlank()) {
                sb.append("｜对应地图：").append(fileName);
            }
            sb.append('\n');
        }

        if (!unassigned.isEmpty()) {
            sb.append("- 未落入任何分区的断面 ").append(unassigned.size()).append(" 条：")
                    .append(unassigned.stream().limit(20).map(RiskRow::name)
                            .collect(java.util.stream.Collectors.joining("、")))
                    .append(unassigned.size() > 20 ? " 等" : "")
                    .append("（坐标缺失或落在自定义边界之外，无对应子图）\n");
        }

        String summary = sb.toString();
        state.context.put("partitionSummary", summary);
        return summary;
    }

    /**
     * 按分区写出断面详情 CSV，并**总是**附上全任务风险段汇总。
     *
     * <p>分区边界与正文/附录**同源**（{@code state.context["partitions"]}，即出图工具回传的
     * tiles[].bbox），归区判定复用 {@code PartitionBounds.contains} 的含边规则 ——
     * 于是「CSV 里某分区的断面数」与报告正文该分区的数字必然一致。
     *
     * <p>不分幅（无 partitions）时：不产分区明细，但**仍然产出 {@code 风险段汇总.csv}**。
     * 风险段的位置/长度/占比与是否分幅无关，这是本次新增的导出内容，不能因为不分幅就消失。
     * 注意因此不分幅的报告现在也会打包成 zip（zip 的触发条件从「有分区 CSV」变成
     * 「有任一 CSV」），前端按报告附录里的 CSV 清单预判文件名，已同步更新。
     */
    private PartitionSectionCsvService.CsvExport writeSectionCsvs(
            String taskId, String taskDir, WorkflowState state) {
        List<Map<String, Object>> partitions = readPartitions(state);
        if (partitions.isEmpty()) {
            try {
                return partitionSectionCsvService
                        .writeRiskSummaryOnly(taskId, new java.io.File(taskDir));
            } catch (Exception e) {
                log.warn("[workflow] 风险段汇总导出失败（不影响报告）: {}", e.getMessage(), e);
                return PartitionSectionCsvService.CsvExport.empty();
            }
        }
        try {
            return partitionSectionCsvService
                    .writePartitionCsvs(taskId, new java.io.File(taskDir), partitions);
        } catch (Exception e) {
            log.warn("[workflow] 分区断面详情 CSV 导出失败（不影响报告）: {}", e.getMessage(), e);
            return PartitionSectionCsvService.CsvExport.empty();
        }
    }

    // ========== 图表收集（逐步落库，支持断点续跑） ==========

    /**
     * 每个出图步骤完成后立即把该步生成的图表路径并入 context["charts"]：
     * 断点续跑时已完成的图表不依赖中间件日志也能恢复。
     */
    private void appendChartsFromTrace(WorkflowState state, int stepIdx, int traceBefore) {
        if (stepIdx != 1 && stepIdx != 4) {
            return;
        }
        List<Map<String, String>> charts = contextCharts(state);
        String agentName = stepIdx == 1 ? "step2_gen_distribution" : "step5_gen_charts";
        List<String> tools = stepIdx == 1
                ? List.of("generate_risk_distribution_map")
                : List.of("generate_scour_heatmap", "generate_section_comparison_chart");

        List<ReasoningTraceMiddleware.ThoughtLogEntry> entries = traceMiddleware.getLog();
        for (int i = traceBefore; i < entries.size() - 1; i++) {
            ReasoningTraceMiddleware.ThoughtLogEntry action = entries.get(i);
            if (!agentName.equals(action.getAgentName()) || !"action".equals(action.getType())) {
                continue;
            }
            String tool = toolOf(action.getContent());
            if (tool == null || !tools.contains(tool)) {
                continue;
            }
            ReasoningTraceMiddleware.ThoughtLogEntry result = entries.get(i + 1);
            if (!"result".equals(result.getType())) {
                continue;
            }
            // 先收分区边界（分幅出图的结果里带「分区明细(JSON)」一行），
            // 后面的图表才能按分区打标；非分幅结果这里是无操作。
            capturePartitions(state, result.getContent());
            // 分幅出图一次返回 N 条路径（r0c0…r0c4）→ **每幅各收一条图表记录**。
            // 只取第一条的话：附录会少 N-1 张图，而且那一条的路径还容易粘上
            // 紧随其后的「分区明细(JSON)」行而变成坏链接。
            for (String path : MarkdownReportService.extractFilePaths(result.getContent())) {
                if (containsPath(charts, path)) {
                    continue;
                }
                Map<String, String> chart = new LinkedHashMap<>();
                chart.put("tool", tool);
                chart.put("result", filePathJson(path));
                appendPartitionTag(chart, state, path);
                charts.add(chart);
            }
        }
        if (!charts.isEmpty()) {
            state.context.put("charts", charts);
        }
    }

    /**
     * 子图文件 → 分区标签。报告附录据此把每张地图归到它所属的分区名下，
     * 与正文的分区小节一一对应。非分幅的图表匹配不到分区，保持原样。
     */
    private static void appendPartitionTag(Map<String, String> chart, WorkflowState state, String path) {
        String fileName = new java.io.File(path).getName();
        List<Map<String, Object>> partitions = readPartitions(state);
        for (int i = 0; i < partitions.size(); i++) {
            Map<String, Object> partition = partitions.get(i);
            if (fileName.equals(String.valueOf(partition.get("file_name")))) {
                chart.put("partitionKey", partitionKey(partition));
                // 序号与正文「分区 N」对齐：partitions 与子图同为行优先顺序
                chart.put("partitionIndex", String.valueOf(i + 1));
                chart.put("partitionLabel", partitionLabel(partition));
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> contextCharts(WorkflowState state) {
        Object cached = state.context.get("charts");
        if (cached instanceof List<?> list) {
            List<Map<String, String>> charts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, String> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), String.valueOf(value)));
                    charts.add(copy);
                }
            }
            return charts;
        }
        return new ArrayList<>();
    }

    private static String toolOf(String content) {
        if (content == null) {
            return null;
        }
        for (String candidate : List.of(
                "generate_risk_distribution_map", "generate_scour_heatmap",
                "generate_section_comparison_chart")) {
            if (content.startsWith(candidate + "(")) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean containsPath(List<Map<String, String>> charts, String path) {
        return charts.stream()
                .anyMatch(chart -> chart.get("result") != null && chart.get("result").contains(path));
    }

    /**
     * 图表路径落库用的 {@code {"file_path":"…"}}。
     *
     * <p>**必须走 JSON 序列化**，不能拼字符串：一旦路径里混进引号（例如尾部粘上了
     * 「分区明细(JSON)：[{…}]」），拼出来的就不是合法 JSON，下游重新解析会在第一个
     * 引号处截断，产出一条不存在的图片路径。
     */
    private String filePathJson(String path) {
        try {
            return json.writeValueAsString(Map.of("file_path", path));
        } catch (Exception e) {
            // 兜底也必须转义，否则又回到「路径里一个引号就把 JSON 截断」的老问题
            return "{\"file_path\":\""
                    + path.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\"}";
        }
    }

    private List<Map<String, String>> collectCharts(WorkflowState state) {
        List<Map<String, String>> charts = contextCharts(state);
        if (!charts.isEmpty()) {
            return charts;
        }
        // 兼容旧逻辑：从中间件全量日志提取（新流程每步已落库，正常不会走到这里）
        for (String p : extractPathsFromMiddleware(traceMiddleware, "step2_gen_distribution", "generate_risk_distribution_map")) {
            charts.add(Map.of("tool", "generate_risk_distribution_map",
                    "result", filePathJson(p)));
        }
        for (String p : extractPathsFromMiddleware(traceMiddleware, "step5_gen_charts", "generate_scour_heatmap")) {
            charts.add(Map.of("tool", "generate_scour_heatmap",
                    "result", filePathJson(p)));
        }
        for (String p : extractPathsFromMiddleware(traceMiddleware, "step5_gen_charts", "generate_section_comparison_chart")) {
            charts.add(Map.of("tool", "generate_section_comparison_chart",
                    "result", filePathJson(p)));
        }
        log.info("[workflow] collectCharts found {} chart(s)", charts.size());
        return charts;
    }

    private List<String> extractPathsFromMiddleware(ReasoningTraceMiddleware middleware, String agentName, String toolName) {
        List<String> paths = new ArrayList<>();
        List<ReasoningTraceMiddleware.ThoughtLogEntry> entries = middleware.getLog();
        String prefix = toolName + "(";
        for (int i = 0; i < entries.size() - 1; i++) {
            ReasoningTraceMiddleware.ThoughtLogEntry action = entries.get(i);
            if (!agentName.equals(action.getAgentName())) continue;
            if (!"action".equals(action.getType())) continue;
            if (!action.getContent().startsWith(prefix)) continue;

            ReasoningTraceMiddleware.ThoughtLogEntry result = entries.get(i + 1);
            if (!"result".equals(result.getType())) continue;

            // 一次工具调用可能产出多幅（分幅出图）→ 全部收下
            List<String> found = MarkdownReportService.extractFilePaths(result.getContent());
            if (found.isEmpty()) {
                log.warn("[workflow] cannot extract path from middleware result: {}", result.getContent());
            } else {
                paths.addAll(found);
            }
        }
        return paths;
    }

    // ========== 辅助方法 ==========

    private ReActAgent buildMiniAgent(String name, Toolkit toolkit, String sysPrompt) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .skillRepositories(skillRepositories)
                .middleware(traceMiddleware)
                .maxIters(3)
                .build();
    }

    private static Map<String, Object> toHighRiskMap(HighRiskSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", section.name());
        // 真 ID 必须落进上下文：断点续跑时 Step 5 靠它取剖面，不能只剩断面名。
        map.put("section_id", section.sectionId());
        map.put("lng", section.lng());
        map.put("lat", section.lat());
        map.put("level", section.level());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readHighRiskSections(WorkflowState state) {
        Object raw = state.context.get("highRiskSections");
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    result.add(copy);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private static int levelOf(Map<String, Object> section) {
        Object level = section.get("level");
        return level instanceof Number number ? number.intValue() : 0;
    }

    // ========== 内部数据类 ==========

    /**
     * 工作流状态。可见性放宽为包级：分区汇总（{@link #buildPartitionSummary}）
     * 需要能被单元测试直接驱动，否则这块逻辑只能靠跑完整工作流才能验证。
     */
    static class WorkflowState {
        final StepState[] stepStates;
        final Map<String, Object> context = new LinkedHashMap<>();
        volatile String lastError;

        WorkflowState() {
            stepStates = new StepState[STEPS.length];
            for (int i = 0; i < STEPS.length; i++) {
                stepStates[i] = StepState.TODO;
            }
        }

        static WorkflowState fromJson(ObjectMapper json, String stepStatesJson, String contextJson) {
            WorkflowState state = new WorkflowState();
            if (stepStatesJson != null && !stepStatesJson.isBlank()) {
                try {
                    List<String> names = json.readValue(
                            stepStatesJson, new TypeReference<List<String>>() { });
                    for (int i = 0; i < state.stepStates.length && i < names.size(); i++) {
                        state.stepStates[i] = StepState.valueOf(names.get(i));
                    }
                } catch (Exception e) {
                    log.warn("[workflow] 解析步骤状态失败，将从头开始: {}", e.getMessage());
                }
            }
            if (contextJson != null && !contextJson.isBlank()) {
                try {
                    state.context.putAll(json.readValue(
                            contextJson, new TypeReference<Map<String, Object>>() { }));
                } catch (Exception e) {
                    log.warn("[workflow] 解析工作流上下文失败，将丢弃旧上下文: {}", e.getMessage());
                }
            }
            return state;
        }
    }

    enum StepState {
        TODO, IN_PROGRESS, DONE, FAILED
    }

    private record StepInfo(String name, String description, String expectedOutcome) {}

    public record HighRiskSection(String name, String sectionId, Double lng, Double lat, int level) {}
}
