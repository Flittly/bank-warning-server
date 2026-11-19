package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 阶段 6 守卫：权限条件必须出现在<b>向量检索那条 SQL</b> 里，且三种身份形态各自正确。
 *
 * <p>这里断言的是 SQL 文本与参数顺序，不是"过滤后结果对不对"——因为结果对不对由
 * {@link KnowledgeServiceTest} 和真库验证覆盖。这一层的价值是：有人把 {@code WHERE} 删了、
 * 或者把 {@code ORDER BY embedding <=>} 改成别名排序（会丢索引），测试立刻红。
 */
@DisplayName("SqlPermissionVectorSearch：权限进 SQL + 分数口径 + fail-closed")
class SqlPermissionVectorSearchTest {

    private static final double[] QUERY = {0.1, 0.2, 0.3};

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    private SqlPermissionVectorSearch dao() {
        return new SqlPermissionVectorSearch(jdbcTemplate, true);
    }

    private static RagProperties props(boolean permissionFilter, boolean publicEnabled) {
        return new RagProperties(true, permissionFilter, publicEnabled, true,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);
    }

    private static Document doc(String docId, double score) {
        Document d = new Document(new DocumentMetadata(
                TextBlock.builder().text("正文").build(), docId, docId + "_c0", Map.of()));
        d.setScore(score);
        return d;
    }

    @Test
    @DisplayName("普通用户 + 公共资料：SQL 同时带 user_id 与 is_public，参数顺序为 向量/身份/向量/条数")
    void ownerOrPublicSqlCarriesBothConditions() {
        dao().search(QUERY, AiCaller.forUser(7L), props(true, true), 15, 0.4);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());

        String text = sql.getValue();
        assertTrue(text.contains("WHERE (user_id = CAST(? AS BIGINT) OR is_public = TRUE)"),
                "权限条件必须进 SQL：" + text);
        assertTrue(text.contains("1 - (embedding <=> ?::vector) AS score"),
                "分数口径必须与 SDK 的 1-distance 一致：" + text);
        assertTrue(text.contains("ORDER BY embedding <=> ?::vector"),
                "必须对索引列直接排序，写成 ORDER BY score 会丢 HNSW 索引：" + text);

        Object[] bound = args.getValue();
        assertEquals(4, bound.length);
        assertEquals(7L, bound[1], "第二个参数必须是 userId");
        assertEquals(15, bound[3], "第四个参数必须是候选条数");
    }

    @Test
    @DisplayName("公共资料开关关闭：退回阶段 1 的严格私有口径")
    void ownerOnlySqlWhenPublicDisabled() {
        dao().search(QUERY, AiCaller.forUser(7L), props(true, false), 10, 0.4);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));

        assertTrue(sql.getValue().contains("WHERE user_id = CAST(? AS BIGINT)"));
        assertFalse(sql.getValue().contains("is_public"), "关开关后不得再提公共资料");
    }

    @Test
    @DisplayName("管理员：SQL 没有任何权限条件（与阶段 4 等价，保证基线可比）")
    void adminSqlHasNoPermissionCondition() {
        dao().search(QUERY, AiCaller.forAdmin(), props(true, true), 15, 0.4);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());

        String text = sql.getValue();
        assertFalse(text.contains("user_id"), "管理员不该被过滤：" + text);
        assertFalse(text.contains("is_public"), "管理员不该被过滤：" + text);
        assertFalse(text.contains("WHERE"), "管理员路径不应有 WHERE：" + text);
        assertEquals(3, args.getValue().length);
    }

    @Test
    @DisplayName("权限过滤总开关关闭：也是无条件检索（沿用阶段 1 的语义）")
    void noFilterWhenPermissionFilterDisabled() {
        dao().search(QUERY, AiCaller.forUser(7L), props(false, true), 15, 0.4);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));

        assertFalse(sql.getValue().contains("user_id"));
    }

    @Test
    @DisplayName("拿不到 userId：直接返回空，绝不查库（fail-closed）")
    void anonymousFailsClosed() {
        SqlPermissionVectorSearch.SearchResult out = dao().search(
                QUERY, AiCaller.anonymous(), props(true, true), 15, 0.4);

        assertTrue(out.docs().isEmpty());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("阈值在 Java 侧筛，不下推 SQL——否则会补位，返回条数多于旧实现")
    void thresholdFiltersInJavaNotInSql() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(doc("high", 0.9), doc("low", 0.1)));

        SqlPermissionVectorSearch.SearchResult out = dao().search(
                QUERY, AiCaller.forUser(7L), props(true, true), 15, 0.4);

        assertEquals(1, out.docs().size());
        assertEquals("high", out.docs().get(0).getMetadata().getDocId());
        assertEquals(1, out.droppedByThreshold(), "被门槛切掉的条数必须能回报，否则空返回原因无从判断");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertFalse(sql.getValue().contains("score >="), "阈值不能下推 SQL：" + sql.getValue());
    }

    @Test
    @DisplayName("关闭态占位实例：拒绝查询，避免静默走错路径")
    void disabledInstanceRefusesToQuery() {
        SqlPermissionVectorSearch off = SqlPermissionVectorSearch.disabled();

        assertFalse(off.isEnabled());
        assertThrows(IllegalStateException.class,
                () -> off.search(QUERY, AiCaller.forUser(7L), props(true, true), 15, 0.4));
    }

    @Test
    @DisplayName("向量文本化：与 pgvector 的 [a,b,c] 字面量一致")
    void vectorLiteralFormat() {
        dao().search(new double[]{0.5, -1.25}, AiCaller.forAdmin(), props(true, true), 5, 0.4);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());

        assertEquals("[0.5,-1.25]", args.getValue()[0]);
    }
}
