package com.yangtze.bankwarning.ai.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 阶段 2：留痕存储必须是「只观测、不添乱」的旁路。 */
@DisplayName("JdbcRagTraceStore：开关与写库失败隔离")
class JdbcRagTraceStoreTest {

    private static RagTrace sampleTrace() {
        return RagTrace.success("t-1", "CHAT", "崩岸判别指标", 3L, 3, 0.4,
                2, 2, List.of(new RagTrace.Hit("BMS.docx", "c0", 0.87)), 12L);
    }

    private static RagProperties withTrace(boolean enabled) {
        return new RagProperties(true, true, true, enabled,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);
    }

    @Test
    @DisplayName("trace-enabled=false 时完全不碰数据库")
    void disabledSkipsWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new JdbcRagTraceStore(jdbc, withTrace(false)).record(sampleTrace());
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("写库失败被吞掉，不向上抛（留痕不能变成新的故障点）")
    void writeFailureDoesNotPropagate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new IllegalStateException("relation ai_rag_traces does not exist"));

        assertDoesNotThrow(() -> new JdbcRagTraceStore(jdbc, withTrace(true)).record(sampleTrace()));
    }

    @Test
    @DisplayName("命中明细是合法 JSON，文档 id 里的引号会被转义")
    void hitsJsonIsValidJson() throws Exception {
        RagTrace trace = RagTrace.success("t-2", "SEARCH", "q", 3L, 5, 0.4,
                1, 1, List.of(new RagTrace.Hit("a\"b", "c0", 0.9)), 1L);

        String json = trace.hitsJson();
        Object parsed = new ObjectMapper().readValue(json, Object.class);
        assertTrue(parsed instanceof List);
        assertTrue(json.contains("a\\\"b"));
    }
}
