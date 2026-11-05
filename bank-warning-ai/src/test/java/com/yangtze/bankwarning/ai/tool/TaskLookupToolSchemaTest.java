package com.yangtze.bankwarning.ai.tool;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归守卫：search_tasks 暴露给 LLM 的 JSON Schema。
 *
 * <p>@ToolParam 的 required 默认是 true。这个工具的两个参数（keyword / limit）都必须是可选，
 * 否则「列出最近任务」这种不传关键词的调用会在 ToolExecutor 的 schema 校验处被直接拒掉，
 * 工具只返回 error —— 而它恰好是模型拿 task_id 的唯一入口，一坏就等于「找不到任务」复发。
 */
@DisplayName("任务检索工具的 JSON Schema 契约")
class TaskLookupToolSchemaTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaOf(String toolName) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TaskLookupTools(null));
        AgentTool tool = toolkit.getTool(toolName);
        assertNotNull(tool, "工具未注册: " + toolName);
        return tool.getParameters();
    }

    @Test
    @DisplayName("search_tasks 必须存在，且关键字与条数都可选")
    void bothParamsAreOptional() {
        Map<String, Object> schema = schemaOf("search_tasks");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.keySet().containsAll(List.of("keyword", "limit")),
                "keyword / limit 必须都在 schema 里");

        Object required = schema.get("required");
        List<String> requiredList = required instanceof List<?> list
                ? list.stream().map(String::valueOf).sorted().toList()
                : List.of();
        assertEquals(List.of(), requiredList,
                "search_tasks 不能有必填参数：留空关键词要能列出最近任务");
    }

    /**
     * 身份参数是「框架注入」的：不带 {@code @ToolParam}、不进 Schema。
     * 一旦它被当成普通参数，模型会看到一个必填的 caller 字段却给不出值，
     * search_tasks 这个唯一能换 task_id 的入口就废了。
     */
    @Test
    @DisplayName("注入的身份参数不得出现在 schema 里")
    void callerParamIsNotExposed() {
        Map<String, Object> schema = schemaOf("search_tasks");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertFalse(properties.containsKey("caller"),
                "AiCaller 泄漏进了 schema：框架没把它识别成上下文字参");
        assertEquals(2, properties.size(), "schema 里只应有 keyword 与 limit");
    }
}
