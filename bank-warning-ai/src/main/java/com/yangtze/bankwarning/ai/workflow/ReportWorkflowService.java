package com.yangtze.bankwarning.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.service.MarkdownReportService;
import com.yangtze.bankwarning.ai.service.VisualizationService;
import com.yangtze.bankwarning.ai.store.ReportRunStore;
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
    private final WeatherTools weatherTools;
    private final List<AgentSkillRepository> skillRepositories;
    private final ReasoningTraceMiddleware traceMiddleware;
    private final MarkdownReportService markdownReportService;
    private final ReportRunStore reportRunStore;
    private final ObjectMapper json = new ObjectMapper();

    public ReportWorkflowService(Model model,
                                 RiskDataTools riskDataTools,
                                 VisualizationTools visualizationTools,
                                 WeatherTools weatherTools,
                                 List<AgentSkillRepository> skillRepositories,
                                 @Qualifier("reportTraceMiddleware") ReasoningTraceMiddleware traceMiddleware,
                                 MarkdownReportService markdownReportService,
                                 ReportRunStore reportRunStore) {
        this.model = model;
        this.riskDataTools = riskDataTools;
        this.visualizationTools = visualizationTools;
        this.weatherTools = weatherTools;
        this.skillRepositories = skillRepositories;
        this.traceMiddleware = traceMiddleware;
        this.markdownReportService = markdownReportService;
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
        log.info("[workflow] start report generation, taskId={}", taskId);
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

            String mdContent = markdownReportService.buildMarkdownContent(
                    taskId, finalReport, collectCharts(state));

            String savedFileName = reportFileName;
            try {
                String mdPath = markdownReportService.generateMarkdownReport(
                        taskId, finalReport, collectCharts(state));
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
            case 4 -> step5GenSectionCharts(state);
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
        toolkit.registerTool(visualizationTools);
        toolkit.removeTool("generate_scour_heatmap");
        toolkit.removeTool("generate_section_comparison_chart");

        ReActAgent agent = buildMiniAgent("step2_gen_distribution", toolkit, """
                你正在执行 Step 2：生成任务的风险分布图。
                必须且只能调用工具 generate_risk_distribution_map，参数 taskId 为：%s。
                返回工具的原始输出。
                """.formatted(taskId));

        Msg out = agent.call(List.of(new UserMessage("user", "开始生成风险分布图"))).block();
        String result = out.getTextContent();
        state.context.put("distributionMapText", result);
        return result;
    }

    private String step3FilterHighRisk(WorkflowState state) {
        String riskDataText = (String) state.context.get("riskDataText");
        if (riskDataText == null) {
            throw new IllegalStateException("Step 1 未执行，无法筛选");
        }
        List<HighRiskSection> highRisk = new ArrayList<>();
        for (String line : riskDataText.split("\n")) {
            if (!line.contains("风险等级")) continue;
            int levelIdx = line.lastIndexOf("风险等级：");
            if (levelIdx < 0) continue;
            String tail = line.substring(levelIdx + "风险等级：".length()).trim();
            int level;
            try {
                level = Integer.parseInt(tail.split("\\s+")[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (level >= 3) {
                String sectionMarker = "断面：";
                int s = line.indexOf(sectionMarker);
                String sectionName = s >= 0
                        ? line.substring(s + sectionMarker.length()).split("（")[0]
                        : "未知";
                highRisk.add(new HighRiskSection(sectionName.trim(), null, null, level));
            }
        }
        // 序列化友好的 Map 形式落库（断点续跑时按 Map 读回）
        List<Map<String, Object>> serializable = new ArrayList<>();
        for (HighRiskSection section : highRisk) {
            serializable.add(toHighRiskMap(section));
        }
        state.context.put("highRiskSections", serializable);
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

    private String step5GenSectionCharts(WorkflowState state) {
        List<Map<String, Object>> highRisk = readHighRiskSections(state);
        if (highRisk.isEmpty()) {
            state.context.put("sectionChartsText", "无高风险断面，跳过断面图生成");
            return "无高风险断面，跳过";
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(visualizationTools);
        toolkit.removeTool("generate_risk_distribution_map");

        StringBuilder sectionList = new StringBuilder();
        for (Map<String, Object> s : highRisk) {
            sectionList.append("断面：").append(s.get("name")).append("（ID 暂用断面名）\n");
        }

        ReActAgent agent = buildMiniAgent("step5_gen_charts", toolkit,
                """
                        你正在执行 Step 5：对每个高风险断面生成冲淤热力图和断面对比图。
                        需要处理的断面列表：%s

                        严格规则：
                        1. 对每个断面依次调用 generate_scour_heatmap(section_id)
                        2. 然后调用 generate_section_comparison_chart(section_id)
                        3. 不要调用 generate_risk_distribution_map（那是 Step 2 干的）
                        4. 把所有图片路径整理成 JSON 返回
                        """.formatted(sectionList));

        Msg out = agent.call(List.of(new UserMessage("user", "开始生成断面图"))).block();
        String result = out.getTextContent();
        state.context.put("sectionChartsText", result);
        return "已为 " + highRisk.size() + " 个高风险断面生成图表";
    }

    private String step6Synthesize(String taskId, WorkflowState state) {
        Toolkit toolkit = new Toolkit();

        String riskData = (String) state.context.getOrDefault("riskDataText", "");
        String dist = (String) state.context.getOrDefault("distributionMapText", "");
        String weather = (String) state.context.getOrDefault("weatherResultsText", "");
        String charts = (String) state.context.getOrDefault("sectionChartsText", "");

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

                === 输出要求 ===
                1. 用中文撰写完整报告
                2. 结构：概述 → 指标分析 → 风险评估 → 叠加天气影响 → 建议措施
                3. 对专业指标做通俗解释
                4. 给出明确的风险等级判定依据
                5. 若天气数据显示未来 24h 累计降水 ≥ 25mm 或 72h 内任一日 ≥ 50mm 或有暴雨/台风/大风预警，必须新增"叠加天气风险"章节
                6. 若 24h 降水 ≥ 50mm 或有红色/橙色预警，必须新增"应急建议"章节
                7. 输出报告正文，不要加任何"以下是报告"之类的开场白
                """.formatted(taskId, riskData, dist, weather, charts);

        ReActAgent agent = buildMiniAgent("step6_synthesize", toolkit, fullPrompt);

        Msg out = agent.call(List.of(new UserMessage("user", "请开始撰写最终报告"))).block();
        String report = out.getTextContent();
        state.context.put("finalReport", report);
        return report;
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
            String path = MarkdownReportService.extractFilePath(result.getContent());
            if (path == null || containsPath(charts, path)) {
                continue;
            }
            charts.add(Map.of("tool", tool, "result", "{\"file_path\":\"" + path + "\"}"));
        }
        if (!charts.isEmpty()) {
            state.context.put("charts", charts);
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

    private List<Map<String, String>> collectCharts(WorkflowState state) {
        List<Map<String, String>> charts = contextCharts(state);
        if (!charts.isEmpty()) {
            return charts;
        }
        // 兼容旧逻辑：从中间件全量日志提取（新流程每步已落库，正常不会走到这里）
        for (String p : extractPathsFromMiddleware(traceMiddleware, "step2_gen_distribution", "generate_risk_distribution_map")) {
            charts.add(Map.of("tool", "generate_risk_distribution_map",
                    "result", "{\"file_path\":\"" + p + "\"}"));
        }
        for (String p : extractPathsFromMiddleware(traceMiddleware, "step5_gen_charts", "generate_scour_heatmap")) {
            charts.add(Map.of("tool", "generate_scour_heatmap",
                    "result", "{\"file_path\":\"" + p + "\"}"));
        }
        for (String p : extractPathsFromMiddleware(traceMiddleware, "step5_gen_charts", "generate_section_comparison_chart")) {
            charts.add(Map.of("tool", "generate_section_comparison_chart",
                    "result", "{\"file_path\":\"" + p + "\"}"));
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

            String path = MarkdownReportService.extractFilePath(result.getContent());
            if (path != null) {
                paths.add(path);
            } else {
                log.warn("[workflow] cannot extract path from middleware result: {}", result.getContent());
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

    private static class WorkflowState {
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

    public record HighRiskSection(String name, Double lng, Double lat, int level) {}
}
