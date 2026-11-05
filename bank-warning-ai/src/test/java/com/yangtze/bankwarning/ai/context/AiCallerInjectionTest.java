package com.yangtze.bankwarning.ai.context;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归守卫：{@link AiCaller} 能不能真的穿过框架到达工具方法。
 *
 * <p>这是整套权限方案的地基。整个设计的假设是：工具方法里那个**不带 {@code @ToolParam}
 * 的 POJO 参数**会被 {@code ToolMethodInvoker} 按类型从 {@code RuntimeContext} 注入，
 * 从而绕过 ThreadLocal 不跨线程的问题。
 *
 * <p>只测「schema 里没有 caller」是不够的 —— 那只能证明模型看不见它，证明不了它送得到。
 * 万一框架换了注入规则，这里会先红：
 * <ul>
 *   <li>传了 context ⇒ 工具拿到的是那个 caller；</li>
 *   <li>没传 context ⇒ 工具拿到 null，而业务侧必须把它当匿名拒绝（fail-closed）。</li>
 * </ul>
 */
@DisplayName("AiCaller 的框架注入链路")
class AiCallerInjectionTest {

    /** 探针工具：只记录收到的身份，不做别的事。 */
    static class ProbeTools {
        AiCaller received;
        boolean invoked;

        @Tool(name = "probe_tool", description = "仅用于测试的探针工具")
        public String probe(@ToolParam(name = "q", required = false) String q, AiCaller caller) {
            this.received = caller;
            this.invoked = true;
            return "ok";
        }
    }

    private ProbeTools callWith(AiCaller injected) {
        Toolkit toolkit = new Toolkit();
        ProbeTools probe = new ProbeTools();
        toolkit.registerTool(probe);

        RuntimeContext.Builder ctx = RuntimeContext.builder();
        if (injected != null) {
            ctx.put(AiCaller.class, injected);
        }
        Map<String, Object> input = Map.of("q", "x");
        // content 是参数 JSON：ToolExecutor 用它做 schema 校验（validateInput(content, schema)），
        // input 才是真正喂给方法的 Map —— 两个都得给，少一个就被校验拦在门外。
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("probe_tool")
                        .content("{\"q\":\"x\"}")
                        .input(input)
                        .build())
                .input(input)
                .runtimeContext(ctx.build())
                .build();
        ToolResultBlock result = toolkit.callTool(param).block();
        assertNotNull(result, "工具调用没有返回结果");
        // 失败时把框架返回的内容带进断言消息，省得只看到一句「没被调用」无从下手
        assertTrue(probe.invoked, "工具没有被调用。框架返回 state=" + result.getState()
                + ", output=" + result.getOutput());
        return probe;
    }

    @Test
    @DisplayName("RuntimeContext 里的 AiCaller 会被注入到工具参数上")
    void callerIsInjectedIntoMethod() {
        AiCaller injected = AiCaller.forUser(7L);

        ProbeTools probe = callWith(injected);

        assertNotNull(probe.received, "工具没收到 AiCaller：框架把它当成普通参数了");
        assertTrue(probe.received.isAuthenticated());
        assertEquals(7L, probe.received.userId(), "注入的身份必须是调用方本人，不能串号");
        assertFalse(probe.received.seeAll());
    }

    @Test
    @DisplayName("管理员身份同样能注入，且 seeAll 为真")
    void adminIsInjected() {
        ProbeTools probe = callWith(AiCaller.forAdmin());

        assertNotNull(probe.received);
        assertTrue(probe.received.seeAll());
    }

    @Test
    @DisplayName("context 里没有 AiCaller 时收到 null —— 业务侧必须按匿名拒绝")
    void missingCallerYieldsNullAndStaysClosed() {
        ProbeTools probe = callWith(null);

        assertNull(probe.received, "没有注入时应当为 null，而不是某个默认管理员");
        // 业务侧的统一兜底：null ⇒ 匿名 ⇒ 看不到任何数据
        assertFalse(AiCaller.orAnonymous(probe.received).seeAll());
        assertFalse(AiCaller.orAnonymous(probe.received).isAuthenticated());
    }
}
