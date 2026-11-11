package com.yangtze.bankwarning.ai.tool;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 1 回归守卫：query_knowledge 新增的身份参数不能被暴露给模型。
 *
 * <p>{@code AiCaller caller} 故意不带 {@code @ToolParam}，由框架按类型从
 * RuntimeContext 注入。一旦它被当成普通参数，模型会看到一个它根本给不出值的
 * 必填字段，Schema 校验就会拒绝这次工具调用 —— 表现为「知识库查了但没结果」，
 * 与本次要消灭的静默失败几乎无法区分。
 */
@DisplayName("知识检索工具的 JSON Schema 契约")
class KnowledgeQueryToolSchemaTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaOf() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new KnowledgeQueryTool(null));
        AgentTool tool = toolkit.getTool("query_knowledge");
        assertNotNull(tool, "query_knowledge 未注册");
        return tool.getParameters();
    }

    @Test
    @DisplayName("schema 里只有 query，身份参数不得泄漏")
    void callerParamIsNotExposed() {
        Map<String, Object> schema = schemaOf();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertNotNull(properties);
        assertFalse(properties.containsKey("caller"),
                "AiCaller 泄漏进了 schema：框架没把它识别成上下文字参");
        assertEquals(1, properties.size(), "schema 里只应有 query");
        assertTrue(properties.containsKey("query"));
    }

    @Test
    @DisplayName("query 仍是必填，避免模型空查")
    void queryRemainsRequired() {
        Object required = schemaOf().get("required");
        List<String> requiredList = required instanceof List<?> list
                ? list.stream().map(String::valueOf).sorted().toList()
                : List.of();
        assertEquals(List.of("query"), requiredList);
    }
}
