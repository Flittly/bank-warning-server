package com.yangtze.bankwarning.ai.tool;

import com.yangtze.bankwarning.ai.context.AiCaller;
import com.yangtze.bankwarning.ai.service.TaskContextService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务检索工具：**唯一**能把「用户嘴里的任务名」换成 {@code task_id} 的入口。
 *
 * <p>为什么必须单独有一个：其余工具（{@code query_risk_data}、{@code generate_risk_distribution_map}…）
 * 的入参都是 {@code task_id}，而 {@code @ToolParam} 默认 required = true —— 模型拿不到 id 时
 * 不会反问，只会**编一个看起来合法的 id**，工具回「未查询到」，用户看到的就是「它找不到我的任务」。
 *
 * <p>两个参数都必须显式 {@code required = false}：{@code @ToolParam} 默认必填，
 * 一旦声明成必填，只传关键词（或什么都不传、只想看最近任务）的调用会在
 * ToolExecutor 的 JSON Schema 校验处被直接拒掉，工具只能返回 error。
 *
 * <h2>{@link AiCaller} 参数为什么没有 {@code @ToolParam}</h2>
 * 这是框架的「上下文字参」约定：{@code ToolMethodInvoker.isUserContextPojo()} 判定
 * ——不带 {@code @ToolParam}、非基本类型、非 {@code ContentBlock}/{@code Msg}、
 * 且包名不在 {@code java.*}/{@code javax.*} 下的参数，**不进 JSON Schema**，而是由
 * {@code resolveContextParameter()} 按类型从 {@code RuntimeContext} 里取出来注入。
 *
 * <p>于是模型既看不见也改不了身份，我们又能在这个跨线程的工具里拿到发起人。
 * <b>代价</b>：注入失败时参数是 null，所以服务层用 {@link AiCaller#orAnonymous} 兜底
 * —— null 一律按匿名拒绝，绝不退化成「看全部」。
 */
public class TaskLookupTools {

    private static final Logger log = LoggerFactory.getLogger(TaskLookupTools.class);
    private final TaskContextService taskContextService;

    public TaskLookupTools(TaskContextService taskContextService) {
        this.taskContextService = taskContextService;
    }

    @Tool(name = "search_tasks",
            description = "按任务名（或任务ID片段）检索任务，返回每个任务的 task_id、状态、断面数、创建时间。"
                    + "用户只说了任务名字、没给 task_id 时，**必须先调用本工具**拿到 task_id，"
                    + "再拿它去调 query_risk_data / generate_risk_distribution_map 等工具；"
                    + "关键词留空则返回最近的任务。任务名可能重名，返回多条时要把清单列给用户确认，"
                    + "不要自己挑一条，也绝对不要编造 task_id。")
    public String searchTasks(
            @ToolParam(name = "keyword", required = false,
                    description = "任务名关键词（支持部分匹配）；留空表示列出最近的任务") String keyword,
            @ToolParam(name = "limit", required = false,
                    description = "最多返回多少个任务，默认 10，最大 30") Integer limit,
            AiCaller caller) {
        log.info("[tool] search_tasks, keyword={}, limit={}, caller={}", keyword, limit, caller);
        return taskContextService.search(keyword, limit, caller);
    }
}
