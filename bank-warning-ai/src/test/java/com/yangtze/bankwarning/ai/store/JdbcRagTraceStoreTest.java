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

    @Test
    @DisplayName("阶段 5：档位与空返回原因必须真的写进参数列表（不是加在对象里就算了）")
    void modeAndEmptyReasonArePersisted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RagTrace trace = RagTrace.success("t-3", "CHAT", "离题的问题", 3L, 3, 0.0,
                0, 0, List.of(), 5L, "hybrid+rerank",
                RagEmptyReason.BELOW_FINAL_FLOOR.code());

        new JdbcRagTraceStore(jdbc, withTrace(true)).record(trace);

        org.mockito.ArgumentCaptor<Object[]> args =
                org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        Object[] bound = args.getValue();
        assertEquals(15, bound.length, "列数改了却漏改 INSERT 是最常见的静默失败");
        assertEquals("hybrid+rerank", bound[13]);
        assertEquals("BELOW_FINAL_FLOOR", bound[14]);
    }

    @Test
    @DisplayName("阶段 5：自举建表必须补 ALTER——CREATE TABLE IF NOT EXISTS 不会给老表加列")
    void bootstrapAltersExistingTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new JdbcRagTraceStore(jdbc, withTrace(true)).init();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(3)).execute(sql.capture());
        String all = String.join("\n", sql.getAllValues());
        assertTrue(all.contains("ADD COLUMN IF NOT EXISTS mode"),
                "表已存在时 CREATE TABLE 整条被跳过，不加 ALTER 就永远缺列：" + all);
        assertTrue(all.contains("ADD COLUMN IF NOT EXISTS empty_reason"), all);
    }

    @Test
    @DisplayName("空返回原因有四种，各自对用户说的话不同；未知值不抛异常")
    void emptyReasonMessagesAreDistinct() {
        long distinct = java.util.Arrays.stream(RagEmptyReason.values())
                .map(RagEmptyReason::message).distinct().count();
        assertEquals(RagEmptyReason.values().length, distinct, "四种原因不能共用一句话");
        assertEquals("", RagEmptyReason.messageOf(null));
        assertEquals("", RagEmptyReason.messageOf("SOMETHING_ELSE"));
    }
}
