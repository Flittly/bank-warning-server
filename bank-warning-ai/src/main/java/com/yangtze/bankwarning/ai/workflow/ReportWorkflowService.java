package com.yangtze.bankwarning.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import com.yangtze.bankwarning.ai.service.MarkdownReportService;
import com.yangtze.bankwarning.ai.tool.RiskDataTools;
import com.yangtze.bankwarning.ai.tool.VisualizationTools;
import com.yangtze.bankwarning.ai.tool.WeatherTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报告生成工作流服务。
 *
 * 设计要点：
 * 1. 6 步硬编码工作流（按钮触发的报告生成是确定性流程，不靠 LLM 自由发挥）
 * 2. 每步独立 mini agent：专属 systemPrompt + 专属 toolkit（仅注册该步需要的工具）
 * 3. executionContext 在 6 步之间共享（替代 LLM memory 跨步传递）
 * 4. 崩溃恢复：state == DONE 的子任务自动跳过
 * 5. 进度可观察：PlanProgress DTO 暴露给前端
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
    private final ObjectMapper json = new ObjectMapper();

    private final ConcurrentHashMap<String, WorkflowState> workflows = new ConcurrentHashMap<>();

    public ReportWorkflowService(Model model,
                                 RiskDataTools riskDataTools,
                                 VisualizationTools visualizationTools,
                                 WeatherTools weatherTools,
                                 List<AgentSkillRepository> skillRepositories,
                                 ReasoningTraceMiddleware traceMiddleware,
                                 MarkdownReportService markdownReportService) {
        this.model = model;
        this.riskDataTools = riskDataTools;
        this.visualizationTools = visualizationTools;
        this.weatherTools = weatherTools;
        this.skillRepositories = skillRepositories;
        this.traceMiddleware = traceMiddleware;
        this.markdownReportService = markdownReportService;
    }

    public String executeTaskReport(String taskId) {
        log.info("[workflow] start report generation, taskId={}", taskId);
        traceMiddleware.clearLog();

        WorkflowState state = workflows.computeIfAbsent(taskId, k -> new WorkflowState());
        state.context.put("taskId", taskId);

        for (int i = 0; i < STEPS.length; i++) {
            if (state.stepStates[i] == StepState.DONE) {
                log.info("[workflow] [{}] skip (already DONE)", STEPS[i].name);
                continue;
            }

            log.info("[workflow] [{}] starting", STEPS[i].name);
            state.stepStates[i] = StepState.IN_PROGRESS;

            try {
                String result = executeOneStep(i, taskId, state);
                state.stepStates[i] = StepState.DONE;
                log.info("[workflow] [{}] DONE", STEPS[i].name);
            } catch (Exception e) {
                log.error("[workflow] [{}] FAILED: {}", STEPS[i].name, e.getMessage(), e);
                state.stepStates[i] = StepState.FAILED;
                state.lastError = e.getMessage();
                throw new RuntimeException("Step " + STEPS[i].name + " failed: " + e.getMessage(), e);
            }
        }

        String finalReport = (String) state.context.getOrDefault("finalReport", "");
        log.info("[workflow] finished, total length={}", finalReport.length());

        try {
            String mdPath = markdownReportService.generateMarkdownReport(
                    taskId, finalReport, collectCharts(state));
            log.info("[workflow] markdown report saved: {}", mdPath);
        } catch (Exception e) {
            log.error("[workflow] failed to save markdown report: {}", e.getMessage(), e);
        }

        return finalReport;
    }

    public PlanProgress getProgress(String taskId) {
        WorkflowState state = workflows.get(taskId);
        if (state == null) {
            return null;
        }

        List<PlanProgress.SubTaskProgress> subProgress = new ArrayList<>();
        int completed = 0;
        for (int i = 0; i < STEPS.length; i++) {
            StepState ss = state.stepStates[i];
            if (ss == StepState.DONE) completed++;
            subProgress.add(new PlanProgress.SubTaskProgress(
                    i, STEPS[i].name, STEPS[i].description, ss.name(),
                    STEPS[i].expectedOutcome, ""));
        }

        String overallStatus = (completed == STEPS.length) ? "FINISHED"
                : (state.lastError != null) ? "FAILED"
                : (completed == 0) ? "PENDING" : "RUNNING";

        return new PlanProgress("report-" + taskId, taskId, overallStatus,
                STEPS.length, completed, subProgress);
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
        state.context.put("highRiskSections", highRisk);
        return "筛选出 " + highRisk.size() + " 个高风险断面（风险等级 >= 3）";
    }

    private String step4QueryWeather(WorkflowState state) {
        @SuppressWarnings("unchecked")
        List<HighRiskSection> highRisk = (List<HighRiskSection>) state.context.get("highRiskSections");
        if (highRisk == null || highRisk.isEmpty()) {
            state.context.put("weatherResults", new LinkedHashMap<String, String>());
            return "无高风险断面，跳过天气查询";
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTools);

        StringBuilder ctx = new StringBuilder("需要查询天气的高风险断面列表：\n");
        for (int i = 0; i < highRisk.size(); i++) {
            HighRiskSection s = highRisk.get(i);
            ctx.append(String.format("%d. 断面：%s | 风险等级：%d | 坐标：(%s, %s)\n",
                    i + 1, s.name(), s.level(), s.lng(), s.lat()));
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
        @SuppressWarnings("unchecked")
        List<HighRiskSection> highRisk = (List<HighRiskSection>) state.context.get("highRiskSections");
        if (highRisk == null || highRisk.isEmpty()) {
            state.context.put("sectionChartsText", "无高风险断面，跳过断面图生成");
            return "无高风险断面，跳过";
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(visualizationTools);
        toolkit.removeTool("generate_risk_distribution_map");

        StringBuilder sectionList = new StringBuilder();
        for (HighRiskSection s : highRisk) {
            sectionList.append("断面：").append(s.name()).append("（ID 暂用断面名）\n");
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

    private List<Map<String, String>> collectCharts(WorkflowState state) {
        List<Map<String, String>> charts = new ArrayList<>();
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

    // ========== 内部数据类 ==========

    private static class WorkflowState {
        final StepState[] stepStates = new StepState[STEPS.length];
        final Map<String, Object> context = new LinkedHashMap<>();
        volatile String lastError;

        WorkflowState() {
            for (int i = 0; i < STEPS.length; i++) {
                stepStates[i] = StepState.TODO;
            }
        }
    }

    enum StepState {
        TODO, IN_PROGRESS, DONE, FAILED
    }

    private record StepInfo(String name, String description, String expectedOutcome) {}

    public record HighRiskSection(String name, Double lng, Double lat, int level) {}
}
