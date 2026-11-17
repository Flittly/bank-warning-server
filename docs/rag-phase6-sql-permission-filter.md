# 阶段 6：SQL 内权限过滤（security trimming）

> 编号作用域：本文所有「阶段 N」均指 **RAG 升级计划**（阶段 4=评测尺子、阶段 5=混合检索、阶段 6=SQL 内权限过滤），
> 与根目录 `docs/global-search-tech-selection.md` 里的 `Phase 1/2/3`（全局搜索框工作线）是两套独立编号。

## 一、先明确：这**不是**修 bug

改动前系统是**正确**的：`applyPermissionFilter()` 会把无权段落筛掉，模型拿不到别人的私有正文。
所以这次改动没有「正在发生的故障」作为依据，它属于**结构改进**。真实存在的三条问题：

| # | 问题 | 现在能复现吗 |
|---|---|---|
| 1 | 普通用户可能拿不满 topK：先取 15 条再筛权限，筛完剩几条看运气（post-filter 破坏 topK 语义） | 不能。库里 4 行全 `is_public=true` 且同属一人 |
| 2 | 无权正文短暂进入 JVM 堆（不返回，但日志/异常栈/heap dump 可能看到） | 不能，除非去 dump 内存 |
| 3 | 每次检索 2 条 SQL（混合态 4 条）、`candidateExpansion=5` 是补丁 | 一直存在，无功能影响 |

**明确不解决**：召回质量（语料问题）、拒答率 0.0000（已证明阈值修不了）、`ai_rag_traces` 0 行（那是没跑过线上检索）。

## 二、部件与挂点

| 部件 | 文件 | 职责 |
|---|---|---|
| 检索 DAO | `service/SqlPermissionVectorSearch.java`（新） | 三份 SQL：无过滤 / 仅本人 / 本人+公共；行转 `Document`；阈值过滤 |
| 接入 | `service/KnowledgeService.java`（改） | 加 7 参 `@Autowired` 构造器并保留 5/6 参重载；dense 分支按开关分派 |
| 配置 | `application.yml.example` | `app.ai.rag.sql-permission-filter-enabled`（默认 `true`） |
| 评测 | `rag/RagEvalCli.java`（改） | 新增 `--sql-filter`，与不加该参数的 `--jdbc` 形成 off/on 对照 |

**没有迁移脚本**：不改表结构，复用 `ai_knowledge_store` 现有列与 `idx_public_ai_knowledge_store_vector`。

## 三、五个关键设计决定

**1. 绕开 SDK，直接用 `JdbcTemplate`**
`SearchDocumentDto` 只有 `vectorName / queryEmbedding / limit / scoreThreshold`，**没有 filter 字段**；
`PgVectorStore` 内部持有 `private final Connection connection`（单连接，`search()` 未加锁）。
这两点决定了「用 SDK 做 SQL 内过滤」不可行。改用 `JdbcTemplate` 的额外收益：连接回到 Spring 连接池，
这是将来上 PostgreSQL RLS 的前置条件（见第六节）。

**2. 三份 SQL 常量，不做运行时拼接**
权限条件只有三种形态，全部写成常量：

```sql
-- 管理员 / permission-filter-enabled=false
SELECT id, doc_id, chunk_id, content, payload, 1 - (embedding <=> ?::vector) AS score
FROM ai_knowledge_store ORDER BY embedding <=> ?::vector LIMIT ?

-- 普通用户，public-knowledge-enabled=false（阶段 1 口径）
... WHERE user_id = CAST(? AS BIGINT) ORDER BY embedding <=> ?::vector LIMIT ?

-- 普通用户，public-knowledge-enabled=true（阶段 2 口径）
... WHERE (user_id = CAST(? AS BIGINT) OR is_public = TRUE) ORDER BY embedding <=> ?::vector LIMIT ?
```

SQL 文本是常量，测试可直接断言三份都带对了条件——这比「运行时兜底再筛一次」更可靠：
兜底查的是同一份数据，`user_id` 逻辑若错，两边一起错。

**3. 分数口径与 SDK 逐字一致（有字节码证据）**
`PgVectorStore.convertDistanceToScore()` 的 COSINE 分支字节码为 `dconst_1; dload_1; dsub`，
即 `1.0 - distance`；`DistanceType.COSINE` 的 `operator` 常量为 `<=>`。
本实现的 `1 - (embedding <=> ?)` 与之逐字相同，因此 `scoreThreshold` 语义不变，4A 基线可比。

**4. `ORDER BY` 必须直接写距离表达式，不能用别名**
HNSW/IVFFlat 只认 `ORDER BY embedding <=> ?` 这种「直接对索引列做距离运算」的形式。
写成 `ORDER BY score DESC` 会失去索引可用性（退化成顺序扫描 + 排序）。代价是查询向量要绑两次参数。
实测（`SET enable_seqscan=off`，4 行小表）确认：

```
Index Scan using idx_public_ai_knowledge_store_vector on ai_knowledge_store
  Order By: (embedding <=> (InitPlan 1).col1)
```

**5. `scoreThreshold` 不下推 SQL**
SDK 的行为是「先取 `limit` 条，再在 Java 里按 `score >= threshold` 筛」。若把阈值写进 `WHERE`，
阈值筛掉的空位会被后面的行补上，返回条数**多于**旧实现，A/B 对照失去可比性。
所以阈值仍在 Java 侧筛，`candidateExpansion` 保留——它现在服务的是「给阈值留后备」，不再是「给权限留后备」。

## 四、验证结果

| 判据 | 做法 | 结果 |
|---|---|---|
| 单元测试 | `SqlPermissionVectorSearchTest`（8 例）+ `KnowledgeServiceTest` 新增 2 例 | 通过 |
| 全量测试 | `mvn test`（core / ai / app 三模块） | **279 + 92，0 失败** |
| 分数口径 | `convertDistanceToScore` 字节码 + `DistanceType.COSINE.operator` 常量 | `1 - d` 与 `<=>` 均一致 |
| 索引可用 | `EXPLAIN` 纯向量排序 | `Index Scan using idx_public_ai_knowledge_store_vector` |
| 权限下推 | `EXPLAIN` 带权限条件 | 出现 `Recheck Cond: ((user_id = 3) OR is_public)` |
| 关闭态零回归 | 5 参构造器 + `RagEvalCli --jdbc` | 走 SDK 路径，行为逐字不变 |

**没验到的**：带 `WHERE` 的 ANN 查询在**真实数据规模**下的计划形状。当前表只有 4 行，
planner 选择 `BitmapOr(user_id, is_public) + Sort`，这是小表的正常选择，不能据此判断数据量上来后会不会退化。
**启用前必须**在语料规模化后重跑 `EXPLAIN (ANALYZE, BUFFERS)`，确认是 `Index Scan` 而不是全表 Sort。
pgvector 0.8 的 iterative scan 是这类「过滤后近邻」的官方补丁，届时视计划形状决定是否依赖。

## 五、回滚

```yaml
app.ai.rag.sql-permission-filter-enabled: false      # 或 RAG_SQL_PERMISSION_FILTER=false
```

关闭后 `doRetrieve()` 走 SDK `vectorStore.search()` + `applyPermissionFilter()`，
执行路径、SQL 条数、日志字段与阶段 4 完全一致。`applyPermissionFilter()` **没有删除**：
关键词路仍在它下面兜一次底，关闭态也依赖它。

## 六、为什么这次不上 PostgreSQL RLS

RLS 是终局形态，但现在直接上会得到一个**看起来打开了、实际没通电**的数据库。实测证据：

| 场景（同一条 `USING (false)` 策略） | 返回行数 |
|---|---|
| `SET ROLE rag_probe`（`rolsuper=f, rolbypassrls=f`） | **0**（策略生效） |
| 同一条策略，`postgres` 身份 + `FORCE ROW LEVEL SECURITY` | **4**（完全绕过，且零报错） |

当前连接用的是 `postgres`（`rolsuper=t` / `rolbypassrls=t`），且 `PgVectorStore` 持单条共享连接、
`search()` 未加锁——`SET LOCAL app.user_id` 会在并发请求间互相覆盖，从「越权读」变成「随机读到别人的数据」，
比现状更糟。

要上 RLS，三个前置条件必须同时成立：非超级用户角色、每请求独占连接（事务内 `SET LOCAL`）、
三层验证探针（启动自检 / 行为探针 / 负向探针）。本次改动只完成了第二块的一半：
**检索连接已经回到 Spring 池里**，剩下「换角色」和「写探针」待做。

登记的触发条件（任一出现再回来看）：第二个真实租户且存在互相不可见的私有文档；
数据通路涨到 4 条以上；要让 BI 工具或外部 API 直连这张表。
