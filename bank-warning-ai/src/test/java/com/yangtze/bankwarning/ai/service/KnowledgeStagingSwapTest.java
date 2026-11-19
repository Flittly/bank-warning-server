package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.store.JdbcRagTraceStore;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.rag.store.VDBStoreBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段二：换名的 SQL 顺序与范围。
 *
 * <p>为什么单独钉顺序：先删旧再换名是唯一安全的次序。反过来先换名，正式 doc_id 下会
 * 瞬间出现「新旧两份」，紧接着的删除会把它们一起删掉——净效果为零。
 *
 * <p>Mockito 对可变参数是按「一个 Object[] 实参」记录的（与 KnowledgeServiceTest 里
 * {@code query(sql, any(RowMapper.class), any(Object[].class))} 的写法一致），
 * 所以这里用 {@link ArgumentCaptor} 抓数组再逐元素断言，而不是按位置 eq。
 */
@DisplayName("KnowledgeService：入库换名（删旧 → 迁入）")
class KnowledgeStagingSwapTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VDBStoreBase vectorStore = mock(VDBStoreBase.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        JdbcRagTraceStore traceStore = mock(JdbcRagTraceStore.class);

        RagProperties props = new RagProperties(true, true, true, true,
                3, 5, 5, 0.4, 5, 15_000, 600_000);
        service = new KnowledgeService(embeddingModel, vectorStore, jdbcTemplate, traceStore, props);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test
    @DisplayName("先删本人旧行，再把临时批次迁到正式 doc_id")
    void deleteBeforePromote() {
        service.replaceStaged("报告.docx#stg-a1", "报告.docx", 7L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(sql.capture(), args.capture());

        List<String> statements = sql.getAllValues();
        List<Object[]> params = args.getAllValues();
        assertTrue(statements.get(0).startsWith("DELETE FROM ai_knowledge_store WHERE doc_id = ?"),
                "第一步必须是删旧行，实际: " + statements.get(0));
        assertArrayEquals(new Object[]{"报告.docx", 7L, 7L}, params.get(0),
                "删除范围必须是「本人名下」，管理员才删全部");
        assertTrue(statements.get(1).startsWith("UPDATE ai_knowledge_store SET doc_id = ?"),
                "第二步才是换名，实际: " + statements.get(1));
        assertArrayEquals(new Object[]{"报告.docx", "报告.docx#stg-a1"}, params.get(1));
    }

    @Test
    @DisplayName("管理员（userId 为空）走同一条 SQL，由 IS NULL 分支删全部")
    void adminDeletesAllOwners() {
        service.replaceStaged("报告.docx#stg-a2", "报告.docx", null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(anyString(), args.capture());

        assertArrayEquals(new Object[]{"报告.docx", null, null}, args.getAllValues().get(0));
    }

    @Test
    @DisplayName("换名一行没改到就抛异常：让事务回滚，不能留下「旧的删了、新的没来」")
    void promoteZeroRowsFailsLoudly() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.replaceStaged("报告.docx#stg-a3", "报告.docx", 7L));
    }

    @Test
    @DisplayName("作废只删临时 doc_id，永不碰正式文档")
    void discardOnlyTouchesStaging() {
        service.discardStaged("报告.docx#stg-a4");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());

        assertTrue(sql.getValue().endsWith("WHERE doc_id = ?"), "作废语句不得带归属条件或别的表");
        assertArrayEquals(new Object[]{"报告.docx#stg-a4"}, args.getValue());
    }
}
