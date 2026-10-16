package com.yangtze.bankwarning.ai.tool;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归守卫：generate_risk_distribution_map 暴露给 LLM 的 JSON Schema。
 *
 * <p>报告工作流 Step 2 的提示词只要求传 taskId（"必须且只能调用工具
 * generate_risk_distribution_map，参数 taskId 为：xxx"）。@ToolParam 的 required
 * 默认是 true，若分幅三参数被声明成必填，LLM 少传就会在 ToolExecutor 的
 * schema 校验处被拒 —— 工具直接返回 error，报告里的风险分布图必然缺失。
 * 因此分幅参数必须显式 required = false。
 */
@DisplayName("出图工具的 JSON Schema 契约")
class VisualizationToolSchemaTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaOf(String toolName) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new VisualizationTools(null));
        AgentTool tool = toolkit.getTool(toolName);
        assertNotNull(tool, "工具未注册: " + toolName);
        return tool.getParameters();
    }

    @Test
    @DisplayName("分幅参数必须是可选：只有 task_id 必填")
    void splitParamsAreOptional() {
        Map<String, Object> schema = schemaOf("generate_risk_distribution_map");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.keySet().containsAll(
                        List.of("task_id", "split_rows", "split_cols", "split_tiles")),
                "分幅参数应仍保留在 schema 中（能力不受影响）");

        Object required = schema.get("required");
        List<String> requiredList = required instanceof List<?> list
                ? list.stream().map(String::valueOf).sorted().toList()
                : List.of();
        assertEquals(List.of("task_id"), requiredList,
                "generate_risk_distribution_map 只应必填 task_id，分幅参数必须可选");
    }
}
