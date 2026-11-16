# 阶段 5：混合检索（dense + sparse → RRF）设计

> 编号作用域：本文所有「阶段 N」均指 **RAG 升级计划**（阶段 4=评测尺子、阶段 5=混合检索、阶段 6=SQL 内权限过滤），
> 与根目录 `docs/global-search-tech-selection.md` 里的 `Phase 1/2/3`（全局搜索框工作线）是两套独立编号。

## 一、要解决的问题

当前 `KnowledgeService.doRetrieve()` 只有**向量检索**一路：问题 → `text-embedding-v4` → pgvector 按余弦距离召回。
它擅长「换词、口语化」的语义匹配，但对**精确字符串**不敏感：向量只知道「这段和问题都在讲 Zb，很像」，
不关心段落里写的是 `20/30/40` 中的哪一组，也不区分 `wNM` 与 `wRE`。

阶段 5 加第二路**关键词召回**（sparse），再用 RRF 把两份名单合成一份。它改的是「喂给模型的参考材料」，
不改模型、不改提示词、不改前端。

**明确不解决**：该拒答时不拒答（4A 基线 0.0000）、权限过滤（阶段 6）、库里没有的资料。
RRF 只会**多**召回一路，不会让结果变空——这一点在 4A 真库实测里已经确认：
该召回题 top1 分 0.4225–0.5673，该拒答题 0.5116–0.6359，区间完全重叠。

## 二、部件与挂点

| 部件 | 文件 | 职责 |
|---|---|---|
| 配置 | `config/HybridRagProperties.java`（新） | 独立 `@Component`，4 参：`enabled=false` / `sparse-top-k=20` / `rrf-k=60` / `max-anchors=8` |
| 关键词路 | `service/SparseRetriever.java`（新） | 提精确锚点 → 拼 SQL（权限内建）→ 行转 `Document` |
| 融合 | `service/RrfFusion.java`（新） | 纯静态，按名次 `1/(k+rank)` 加权求和 |
| 接入 | `service/KnowledgeService.java`（改） | 加 6 参 `@Autowired` 构造器并保留 5 参重载；`doRetrieve()` 分叉到 `hybridOutcome()` |
| 索引 | `migration/V17__rag_hybrid_content_trgm.sql`（新） | 只落盘不执行，等语料规模化再建 |

## 三、六个关键设计决定

**1. 默认关闭，关闭态逐字不变**
`hybrid-enabled=false` 时 `doRetrieve()` 走原来的代码路径：不构造 sparse SQL、不跑 RRF、日志字段不变。
`KnowledgeService` 的 5 参构造器被 5 处直接 `new`（4 处测试 + `RagEvalCli`），全部委托到「关闭态」，
一行都不用改。`RagProperties` 的 11 参构造器（被 7 处 `new`）**一字未动**，新配置独立成类，
做法与 `ReportRagProperties` 一致。

**2. 只抓「精确锚点」，不做通用全文检索**
中文没有空格分词，PostgreSQL `to_tsvector` 会把整句当一个词（项目已实测）。所以只提两类锚点：

- ASCII 标识符，≥2 字符：`Zb` / `PL` / `LC` / `hs` / `hc` / `Dsed` / `wNM` / `ValueError`；
- 数字，≥2 位：`20` / `30` / `40` / `42` / `13.5` / `50286`。

阈值是**刻意压低**的：本项目要伺候的正是两字符代号，若要求 ≥3 字符，`Zb`/`PL`/`LC`/`hs`/`hc` 全部提不出来，
关键词路等于没做。代价是个别英文停用词会混进来，用一张小停用词表挡掉最常见的那些。

**一个锚点都提不出来就直接短路返回空、不查库**。纯中文语义问题（「崩岸风险怎么判」）因此自动退回 dense-only。

**3. 正文取值必须走 `content::jsonb ->> 'text'`**
`ai_knowledge_store.content` 存的是 SDK 写入的 **JSON 包装** `{"type":"text","text":"正文…"}`，不是纯文本。
实测 `content ILIKE '%text%'` 命中 **100%** 的行——命中的是 JSON 键名。所以：
- 匹配条件、返回正文、V17 索引表达式，三者统一用 `(content::jsonb ->> 'text')`；
- 若有人手工插入纯文本行，`::jsonb` 会抛 `invalid input syntax`，关键词路上抛 →
  `KnowledgeService` 记 warn 并降级 dense-only。**禁止手工插入非 JSON 的 content**。

**4. 权限条件内建在 sparse SQL 里**
不沿用「先召回后过滤」。否则等于把 `applyPermissionFilter()` 那个「无权正文短暂进 JVM」的弱点
**复制一份到第二路，而且这次是明文**。普通用户 SQL 追加
`AND (user_id = CAST(? AS BIGINT) OR is_public = TRUE)`（`public-knowledge-enabled=false` 时退回严格私有），
管理员整段去掉。`KnowledgeService` 仍再过滤一次（幂等、防御纵深）。

**5. 单路有结果时不做融合，保留原始分数**
纯中文问题 sparse 为空 → 直接返回 dense 列表，**不改写 `score`**。
若此时也走 RRF，余弦相似度会被换成无量纲 RRF 分，导致「开关打开但无锚点」与关闭态结果形式不一致，
既破坏 4A 基线可比性，也让留痕里的 `score` 不可解释。

**6. 关键词路失败降级，但必须留痕**
sparse 抛异常 → `log.warn` 打 `sparse 路失败，本次降级为 dense-only`，返回 dense 结果。
不把原本可用的 RAG 打死，也不重演阶段 1 之前的「静默失败」：降级在日志里可见。

## 四、SQL 形状

```sql
SELECT id, doc_id, chunk_id, (content::jsonb ->> 'text') AS body
FROM ai_knowledge_store
WHERE ((content::jsonb ->> 'text') ILIKE ? ESCAPE '\'
    OR (content::jsonb ->> 'text') ILIKE ? ESCAPE '\')          -- 每个锚点一个
  AND (user_id = CAST(? AS BIGINT) OR is_public = TRUE)          -- 普通用户；管理员无此段
ORDER BY (
    CASE WHEN (content::jsonb ->> 'text') ILIKE ? ESCAPE '\' THEN 1 ELSE 0 END
  + CASE WHEN ... THEN 1 ELSE 0 END
) DESC, id                                                       -- 命中锚点多的在前
LIMIT ?;
```

通配符 `%` / `_` / `\` 在 Java 侧统一转义，避免用户输入变成「匹配任意串」。

## 五、索引用不用得上

V17 把 trigram 索引建在**同一个表达式**上：

```sql
CREATE INDEX ... ON ai_knowledge_store
    USING gin (((content::jsonb ->> 'text')) gin_trgm_ops);
```

表达式不一致（比如索引建在 `content`、查询走 `->> 'text'`）索引就是废的。
不建索引不会让功能失效，只是 sparse 走顺序扫描。**现在不执行**：库里只有 4 段。

## 六、留痕口径

`ai_rag_traces` 表结构、列、写入路径**全部不变**，没有 migration。
`hits_json[].score` 的语义随开关变化：

| 开关 | score 含义 | 可比性 |
|---|---|---|
| `hybrid-enabled=false` | 余弦相似度（0~1，越大越像） | 与 4A 基线一致 |
| `hybrid-enabled=true` 且两路都有结果 | 无量纲 RRF 分（约 0.01~0.03） | 只在同开关内横向比，不与余弦比 |
| `hybrid-enabled=true` 但 sparse 为空 | 仍是余弦相似度 | 与关闭态一致 |
| `hybrid-enabled=true` 且 dense 为空、sparse 有结果 | `null`（不伪造余弦分） | 报告 `top1分` 列显示 `—`，但 `返回` 列有 docId |

看留痕判断「这次走没走混合」的方法：看日志里的 `hybrid=on`，或看 `score` 量级。

## 七、怎么验证（现在只能验逻辑，验不了收益）

**能验的（已做，进 CI）**：
- 关闭态零回归：`KnowledgeServiceTest` 断言关闭时`jdbc.query(...)` 一次都不调用；
- 锚点提取：`Zb 的…20 30 40`、`PL 和 LC…ValueError`、`Dsed…hs 42…hc 13.5` 三组；
- 融合正确性：共有 chunk 分数相加、排名上升；单路时原样返回且分数不变；
- 权限：普通用户 SQL 含 `user_id` / `is_public`，管理员不含；fail-closed；
- 降级：sparse 抛异常时 dense 结果照常返回。

**验不了收益的原因（这是当前的真实状态）**：
考卷 D01/D02 期望 `template.json`、D03 期望 `riskLevel.py`，这两份**都不在库**；
D04 期望的 `BMS.docx` 在库且本就被向路 100% 召回。所以此刻开/关两轮跑 17 题，
汇总指标会逐项相同——**不是代码没写好，是库里没有可区分的对象**。

### 7.1 2026-11-16 真库实测记录

`mvn -pl bank-warning-ai -am test` → **269 tests / 0 failures / 0 errors**（基线 245 + 新增 24）。

| 轮次 | 计分题 | Recall | Precision | 拒答通过率 | 异常 | 与 4A 基线 |
|---|---|---|---|---|---|---|
| OFF（`--jdbc`） | 4 | 1.0000 | 1.0000 | 0.0000 | 0 | 逐项一致 |
| ON（`--jdbc --hybrid`） | 4 | 1.0000 | 1.0000 | 0.0000 | 0 | 一致 |

**开/关的差异只出现在不计分的 B04 与 D03**，而这正是设计要证明的机制：

| 题 | 问题 | OFF 返回 | ON 返回 |
|---|---|---|---|
| B04 | PL 和 LC 有哪些合法取值 | 空 | `BMS.docx` |
| D03 | PL 和 LC 传入非法值时会抛 ValueError 吗 | 空 | `BMS.docx` |

D03 的日志是 `dense=0, denseVisible=0, sparse=1` —— **向量路一条都没召回，关键词路独立捞回 1 段**。
两题都带 `PL` / `LC` / `ValueError` 这类短代号，正是向量路的盲区、锚点路的靶子。

但要如实说明：命中的是 `BMS.docx`，两题期望的 `riskLevel.py` 不在库里，所以**计分上仍不算命中**。
这证明的是「机制通了」，不是「答对了」。等 `riskLevel.py` 入库，这两题才会从不计分转成计分并看出真实收益。

语料到位后按这个顺序验：

```bash
# 1) 关闭态基线（应与 4A 报告逐项一致）
mvn -pl bank-warning-ai exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yangtze.bankwarning.ai.rag.RagEvalCli \
  -Dexec.args="--jdbc --out target/rag-off.md"

# 2) 打开混合检索
mvn -pl bank-warning-ai exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yangtze.bankwarning.ai.rag.RagEvalCli \
  -Dexec.args="--jdbc --hybrid --out target/rag-on.md"

# 3) 逐题对比：D01~D04 的 returned 是否出现 template.json / riskLevel.py
```

## 八、开工/启用门槛

写代码不需要门槛（已完成），**启用**需要：

1. 知识库不同 `doc_id` ≥ 5（当前 1）；
2. D 类期望文档已入库（缺 `template.json`、`riskLevel.py`）；
3. V17 索引已手动执行（当前 0 根 trigram）；
4. 4B 复测完成，off/on 对照报告能看出差异。

四条齐了把 `RAG_HYBRID_ENABLED` 置 `true`；任何一条不满足就保持 `false`——功能在，只是不通电。
