package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.ChineseKeywordProperties;
import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.context.AiCaller;
import io.agentscope.core.rag.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 阶段 5：关键词路的锚点提取、权限内建、正文取值与降级语义。
 *
 * <p>不连数据库：{@link JdbcTemplate} 全部 mock。断言的重点不是 SQL 能不能跑，
 * 而是「权限条件有没有写进第二路」「正文有没有取 JSON 里的 text」「异常有没有被吞」。
 */
@DisplayName("阶段 5：关键词召回（锚点 / 权限内建 / 降级）")
class SparseRetrieverTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
    }

    private SparseRetriever retriever(RagProperties props) {
        return new SparseRetriever(jdbc, props, HybridRagProperties.enabledWithDefaults());
    }

    private SparseRetriever retriever(RagProperties props, ChineseKeywordProperties chinese) {
        return new SparseRetriever(jdbc, props, HybridRagProperties.enabledWithDefaults(), chinese);
    }

    /** 捕获实际发出的 SQL，用于断言权限片段与正文表达式。 */
    private String captureSql() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(cap.capture(), any(RowMapper.class), any(Object[].class));
        return cap.getValue();
    }

    // ---------------- 锚点提取 ----------------

    @Test
    @DisplayName("D01：Zb 阈值题提出 Zb 与 20/30/40")
    void extractsAnchorsFromThresholdQuestion() {
        List<String> anchors = SparseRetriever.extractAnchors(
                "Zb 的三级阈值 20 30 40 分别对应哪个风险等级", 8);

        assertTrue(anchors.contains("Zb"));
        assertTrue(anchors.containsAll(List.of("20", "30", "40")));
    }

    @Test
    @DisplayName("D03：两字符枚举名 PL / LC 与异常名 ValueError 都要提出来")
    void extractsShortIdentifierAndExceptionName() {
        List<String> anchors = SparseRetriever.extractAnchors(
                "PL 和 LC 传入非法值时会抛 ValueError 吗", 8);

        assertTrue(anchors.contains("PL"), "两字符代号是本题的靶子，锚点阈值不能设成 ≥3 字符");
        assertTrue(anchors.contains("LC"));
        assertTrue(anchors.contains("ValueError"));
    }

    @Test
    @DisplayName("D04：公式里的 hs / hc 与 42 / 13.5")
    void extractsFormulaAnchors() {
        List<String> anchors = SparseRetriever.extractAnchors(
                "Dsed 算例中 hs 42 除以 hc 13.5 得到的具体数值", 8);

        assertTrue(anchors.containsAll(List.of("Dsed", "hs", "hc", "42", "13.5")));
    }

    @Test
    @DisplayName("纯中文语义问题：一个锚点都没有，自动退回 dense-only")
    void pureChineseQuestionYieldsNoAnchors() {
        assertTrue(SparseRetriever.extractAnchors("崩岸风险等级是怎么判定的", 8).isEmpty());
    }

    @Test
    @DisplayName("英文停用词与单个数字被过滤，避免召回噪音")
    void filtersStopWordsAndSingleDigit() {
        List<String> anchors = SparseRetriever.extractAnchors("what is the 3 m 高度", 8);

        assertFalse(anchors.contains("what"));
        assertFalse(anchors.contains("the"));
        assertFalse(anchors.contains("3"), "单数字会把整表都召回来");
    }

    @Test
    @DisplayName("maxAnchors 生效，且同一标识符大小写只算一次")
    void respectsMaxAndDeduplicatesCaseInsensitively() {
        List<String> anchors = SparseRetriever.extractAnchors(
                "Zb zb ZB wNM wRE wGE wRL Dsed PL LC", 3);

        assertEquals(3, anchors.size());
        assertEquals(3, anchors.stream().map(String::toLowerCase).distinct().count());
    }

    @Test
    @DisplayName("LIKE 模式串转义 % 与 _，用户输入不能变成通配符")
    void escapesLikeWildcards() {
        assertEquals("%50\\%\\_off%", SparseRetriever.like("50%_off"));
    }

    // ---------------- SQL 与权限 ----------------

    @Test
    @DisplayName("普通用户：sparse SQL 自带 user_id 与 is_public，正文取 JSON 里的 text")
    void normalUserSqlCarriesPermissionAndTextExpression() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(RagProperties.defaults()).retrieve("Zb 20 30", AiCaller.forUser(7L), 20);

        String sql = captureSql();
        assertTrue(sql.contains("user_id = CAST(? AS BIGINT)"), "可空 userId 必须显式 CAST，否则 pgjdbc 报无法确定参数类型");
        assertTrue(sql.contains("is_public = TRUE"), "公共资料必须进入第二路的可见范围");
        assertTrue(sql.contains("content::jsonb ->> 'text'"),
                "content 列存的是 JSON 包装，匹配必须取 ->> 'text'，否则 content ILIKE '%text%' 会命中所有行");
        assertTrue(sql.contains("ORDER BY"), "多锚点命中应排在前面");
    }

    @Test
    @DisplayName("管理员：SQL 不带任何权限条件")
    void adminSqlHasNoPermissionClause() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(RagProperties.defaults()).retrieve("Zb 20", AiCaller.forAdmin(), 20);

        String sql = captureSql();
        assertFalse(sql.contains("user_id"));
        assertFalse(sql.contains("is_public"));
    }

    @Test
    @DisplayName("public-knowledge-enabled=false：退回严格私有口径")
    void strictPrivateWhenPublicKnowledgeDisabled() {
        RagProperties strict = new RagProperties(true, true, false, true,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(strict).retrieve("Zb 20", AiCaller.forUser(7L), 20);

        String sql = captureSql();
        assertTrue(sql.contains("user_id = CAST(? AS BIGINT)"));
        assertFalse(sql.contains("is_public"));
    }

    @Test
    @DisplayName("permission-filter-enabled=false：与 applyPermissionFilter 同口径，不加条件")
    void noConditionWhenPermissionFilterDisabled() {
        RagProperties noFilter = new RagProperties(true, false, true, true,
                3, 5, 5, 0.4, 5, 15_000L, 600_000L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(noFilter).retrieve("Zb 20", AiCaller.forUser(7L), 20);

        String sql = captureSql();
        assertFalse(sql.contains("user_id"));
        assertFalse(sql.contains("is_public"));
    }

    // ---------------- 行为边界 ----------------

    @Test
    @DisplayName("关掉中文词项且提不出锚点：一条 SQL 都不发（阶段 5 行为）")
    void noAnchorsAndNoChineseMeansNoQuery() {
        List<Document> docs = retriever(RagProperties.defaults(), ChineseKeywordProperties.disabled())
                .retrieve("崩岸风险怎么判", AiCaller.forAdmin(), 20);

        assertTrue(docs.isEmpty());
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("阶段 3：纯中文提问不再空返回——发 SQL 且走 search_text 的 tsvector")
    void pureChineseQuestionQueriesChineseIndex() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(RagProperties.defaults()).retrieve("崩岸风险等级是怎么判定的", AiCaller.forAdmin(), 20);

        String sql = captureSql();
        assertTrue(sql.contains("to_tsvector('simple', search_text)"),
                "倒排索引表达式必须与 V19 的索引定义逐字一致，否则索引失效");
        assertTrue(sql.contains("to_tsquery('simple', ?)"));
        assertTrue(sql.contains("ts_rank_cd("), "中文词项要按相关度排序，不能只按命中与否");
        assertFalse(sql.contains("ILIKE"), "纯中文提问没有锚点，不该出现锚点条件");
    }

    @Test
    @DisplayName("阶段 3：中文词项与锚点并存时是 OR 关系，排序优先锚点数")
    void chineseTermsAndAnchorsAreOred() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(RagProperties.defaults())
                .retrieve("Zb 的三级阈值分别是什么", AiCaller.forAdmin(), 20);

        String sql = captureSql();
        assertTrue(sql.contains("to_tsquery('simple', ?) OR ("), "两套词项必须是 OR，否则一半候选被布尔条件挡掉");
        assertTrue(sql.contains("THEN 1 ELSE 0 END"), "锚点命中数仍是第一排序键");
        assertTrue(sql.contains("rank DESC NULLS LAST"),
                "DESC 默认 NULLS FIRST，未回填 search_text 的行会排到最前，必须显式 NULLS LAST");
    }

    @Test
    @DisplayName("阶段 3：关闭中文词项后 SQL 里没有 tsquery，退回纯锚点（可随时回滚）")
    void chineseDisabledFallsBackToAnchorsOnly() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(RagProperties.defaults(), ChineseKeywordProperties.disabled())
                .retrieve("Zb 的三级阈值分别是什么", AiCaller.forAdmin(), 20);

        String sql = captureSql();
        assertFalse(sql.contains("to_tsquery"), "关闭态必须与阶段 5 逐字一致，不能残留中文条件");
        assertFalse(sql.contains("ts_rank_cd"));
        assertTrue(sql.contains("ILIKE"));
    }

    @Test
    @DisplayName("阶段 3：参数按占位符顺序绑——rank 的 tsquery 必须在锚点模式串之前")
    void bindsParametersInPlaceholderOrder() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        retriever(RagProperties.defaults())
                .retrieve("Zb 的三级阈值", AiCaller.forAdmin(), 20);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), args.capture());
        Object[] values = args.getValue();
        // 顺序：rank 的 tsquery → where 的 tsquery → 锚点 ILIKE → order 的锚点 ILIKE → limit
        assertTrue(String.valueOf(values[0]).contains("'"), "第一个参数应是 tsquery 表达式");
        assertEquals(values[0], values[1], "rank 与 where 用的是同一个 tsquery");
        assertEquals("%Zb%", values[2]);
        assertEquals(20, values[values.length - 1]);
    }

    @Test
    @DisplayName("行映射：正文取 JSON 的 text，docId / chunkId 落到 metadata")
    void mapsRowToDocument() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("body")).thenReturn("Zb 阈值 20 30 40");
        when(rs.getString("doc_id")).thenReturn("template.json");
        when(rs.getString("chunk_id")).thenReturn("template.json_chunk_0");

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Document> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<Document> docs = retriever(RagProperties.defaults())
                .retrieve("Zb 20 30 40", AiCaller.forAdmin(), 20);

        assertEquals(1, docs.size());
        assertEquals("template.json", docs.get(0).getMetadata().getDocId());
        assertEquals("template.json_chunk_0", docs.get(0).getMetadata().getChunkId());
        assertEquals("Zb 阈值 20 30 40", docs.get(0).getMetadata().getContentText());
    }

    @Test
    @DisplayName("正文为空的段落被丢弃，不会把空 Document 交给融合")
    void blankBodyIsDropped() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("body")).thenReturn("   ");
        when(rs.getString("doc_id")).thenReturn("x");
        when(rs.getString("chunk_id")).thenReturn("x_c0");

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Document> mapper = inv.getArgument(1);
                    return java.util.Arrays.asList(mapper.mapRow(rs, 0), null);
                });

        List<Document> docs = retriever(RagProperties.defaults())
                .retrieve("Zb 20", AiCaller.forAdmin(), 20);

        assertTrue(docs.isEmpty());
    }

    @Test
    @DisplayName("JDBC 异常向上抛，由上层降级并留痕，不在这里吞成空列表")
    void jdbcFailurePropagates() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("content 不是合法 JSON"));

        assertThrows(DataAccessResourceFailureException.class,
                () -> retriever(RagProperties.defaults())
                        .retrieve("Zb 20", AiCaller.forAdmin(), 20));
    }
}
