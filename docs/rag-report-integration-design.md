# RAG 报告轨接入设计（定稿待实施）

> 状态：**设计定稿，未实施**。本文只描述「更新后，生成评估报告时能参考 RAG 知识库」的落地方案，不含任何业务代码改动。
> 位置说明：本文放在 `bank-warning-server/docs/`（server 仓库内、已确认 `.gitignore` 不排除），可随 git 带走；
> 早期 `docs/rag-criteria-alignment-draft.md` 在根目录 `docs/`，不属任何子仓库，换机不带，不要再往那里放新文档。

---

## 0. 一句话目标

用户在结果页点「生成报告」时，Step 6 撰写正文所引用的**风险等级判定阈值、指标定义、权重口径**，
必须来自知识库中已审定的条目，而不是模型凭参数记忆自由发挥；正文要能说明依据的是哪一段，
库里没有时明确写「知识库未提供」，不许编造。

对话轨（`/chat/stream`）已经查知识库；本次只补**报告轨**这一半。

---

## 1. 现状事实核查（含对前几轮结论的更正）

### 1.1 报告轨今天「完全没有」查知识库的能力 —— 更正

前一轮我写过「`reportToolkit` 已注册 `knowledgeQueryTool`，身份也已就绪，只是提示词从没要求它查」。
**这是错的**。实查：

| 事实 | 证据 |
|---|---|
| `reportToolkit` 确实注册了 `knowledgeQueryTool` | `AgentScopeConfig.java:183-198`，`:194` `toolkit.registerTool(knowledgeQueryTool)` |
| 但它**只被对话 Agent 使用** | `AgentScopeConfig.java:277-301` `chatAgent(...)` 是 `HarnessAgent`，`.toolkit(reportToolkit)`，`maxIters(15)` |
| 报告工作流**每一步都自己 new 一个 Toolkit** | `ReportWorkflowService.java:401` / `:417` / `:482` / `:519` / `:589` 全是 `new Toolkit()` 后按步只注册该步工具 |
| **Step 6 的 Toolkit 是空的** | `:589` `Toolkit toolkit = new Toolkit();` → `:639` `buildMiniAgent("step6_synthesize", toolkit, fullPrompt)`，中间没有任何 `registerTool` |
| mini agent 迭代预算 3 次 | `:1147-1157` `buildMiniAgent()` `.maxIters(3)` |
| `toolContext()` 确实带了身份 | `:393-397` `RuntimeContext.builder().put(AiCaller.class, caller).build()` |

结论：Step 6 的 agent **手里没有任何工具**，`query_knowledge` 根本不在它的工具表里。
所以「让 Agent 自己决定要不要查」这条路在报告轨**当前不存在**，不是「提示词没要求」而是「压根没挂」。
这反而让方案更干净：只能、也应该走「服务端固定预检索 → 拼进 prompt」。

### 1.2 「报告加引用要前后端同改 SSE」—— 更正

前一轮我把 `chatStream.ts:19-38,117-118`（未知事件 `return null`）挂到了报告轨，**这是错的**。
`chatStream.ts` 只解析 `POST /chat/stream` 的 SSE 帧，与报告无关。

报告链路是**同步 HTTP + 落盘 Markdown**：

- `AgentController.java:98` `@PostMapping("/report/task/{task_id}")` → `:115` `executeTaskReport(...)`（同步，`@Async` 全仓无命中）；
- `ReportWorkflowService.java:221` 生成 `report_<taskId>_<ts>.md`，`:264-275` `MarkdownReportService` 落盘；
- `MarkdownReportService.java:69-75` 直接 `md.append(reportText)` 把 Step 6 输出写进正文；
- 前端按返回的 `filename` 打开查看器渲染 md。

⇒ **报告里加「依据来源」就是往正文里写几行文本，零前端改动、零 SSE 改动。**
「可点击引用 + 结构化来源面板」才是需要前后端同改的第二步，本次不做。

### 1.3 「检索快照要写进 ai_report_runs / 需 V17」—— 更正为不需要

`ReportWorkflowService.java:355-357` `toContextJson(state)` 把整个 `state.context` 序列化成
`ai_report_runs.context_json`，`:1215-1231` `fromJson` 用 `TypeReference<Map<String,Object>>` 读回。
所以**检索快照天然随 checkpoint 落库**，不需要新表、不需要 V17 加列。

但有硬约束：放进去的必须是**纯 JSON 可序列化类型**（`String` / `Map` / `List`），
**不能放 `Document` 对象**（内含 `double[] embedding`，序列化会炸或污染 checkpoint）。
因此注入用的上下文与快照都先渲染成字符串再存。

### 1.4 其余已核实事实（沿用）

- 全系统唯一检索入口 `KnowledgeService.retrieve(query, scenario, caller, limitOverride, traceId)`：`:96`。
- 现有 3 个调用点：`AgentController.java:236`(CHAT)、`KnowledgeController.java:254`(SEARCH)、`KnowledgeQueryTool.java:37`(TOOL)。
- `Scenario` 枚举 `KnowledgeService.java:50` 为 `{ CHAT, TOOL, SEARCH }`；`topKFor():232-238` 是**穷举 switch 表达式**。
  ⇒ 加 `REPORT` 必须**同时**在 `topKFor` 加 `case REPORT`，否则编译失败。这是硬约束，不是可选项。
- `RagProperties` 构造器是 11 参数（`:50-73`），测试里 5 处直接 `new RagProperties(...)`：
  `KnowledgeServiceTest.java:123/154/167/268`、`JdbcRagTraceStoreTest.java:25`，另有 `defaults():77`。
  ⇒ **不能给该构造器加参数**，否则 5 处测试同时编译失败。
- `ReportWorkflowService` 构造器是 10 参数（`:107-127`），
  `ReportWorkflowPartitionTest.java:42` 与 `:282` 直接 `new ReportWorkflowService(null × 10)`。
  ⇒ **同样不能改构造器签名**，否则这 2 处编译失败。
- `state.caller` 每次运行在请求线程重新赋值：`:188` `state.caller = AiCaller.of(SecurityUtils.getDataScope())`；
  `toolContext():393-397` 的兜底是「state.caller 为空则当前线程重取」。
- `KnowledgeService.retrieve()` 失败抛 `KnowledgeUnavailableException`（DISABLED / UNCONFIGURED /
  UNAUTHENTICATED / EMBED_FAILED / SEARCH_FAILED），且会写 `ai_rag_traces`。
- 报告 Step 6 是 `executeOneStep()` 的第 6 步（`:377`）；任一步抛异常会 `:246-253` 把整份报告标 FAILED。

---

## 2. 设计判据

| # | 判据 | 理由 |
|---|---|---|
| 1 | 知识库做成 **Step 6 的一个固定输入段落**，与 `riskData / dist / weather / charts / partitionSummary` 同级 | 报告是 6 步确定性固定链路；加「Agent 可选工具调用」会让同一任务两次生成一次查了一次没查，破坏可复现性 |
| 2 | **不给 Step 6 的 Toolkit 注册 `knowledgeQueryTool`** | 它今天是空的，`maxIters(3)` 也没余量；注册了就引入不确定性，且工具自主检索走不到统一治理 |
| 3 | 复用 `KnowledgeService.retrieve()` 唯一入口，不新建平行检索器 | 阶段 1 收口的红利：权限过滤、公共知识、失败透出、`ai_rag_traces` 留痕全部自动生效 |
| 4 | query 用**固定字符串常量**，不拼任务动态信息 | 保证「同任务 + 同语料版本 → 同检索结果」；否则每次报告检索内容都不同 |
| 5 | 失败**不阻断报告**，但要留痕、要在正文标注 | 知识库挂了照样出报告是可用性要求；但必须显式写「知识库不可用：<reason>」，不许静默当成没有资料 |
| 6 | 开关默认 **false** | 库里当前只有 4 段 `BMS.docx`（内部算法说明，不是判定标准）。开了会批量污染正式交付物 |

---

## 3. 具体改动清单（6 处，全部在 `bank-warning-ai` 模块）

| # | 文件 / 位置 | 改动 |
|---|---|---|
| 1 | `KnowledgeService.java:50` `enum Scenario` | 加 `REPORT`；**同步**在 `topKFor():232-238` 加 `case REPORT -> props.reportTopK`（漏了编译不过） |
| 2 | 新建 `ReportRagProperties.java`（`config` 包，`@Component`） | 独立配置类，避免动 `RagProperties` 的 11 参构造器：字段 `reportRetrievalEnabled`（`${app.ai.rag.report-enabled:false}`）、`reportTopK`（`:3`）、`reportQuery`（固定中文 query 常量）。理由见 §1.4 |
| 3 | `RagProperties.java` | **零改动**。`topKFor` 的 `case REPORT` 走 `reportProps.reportTopK`，或更省事：在 `KnowledgeService` 里对 REPORT 直接用 `limitOverride` 由报告侧传入，连 `reportTopK` 都不必进 `RagProperties` |
| 4 | `ReportWorkflowService.java` 依赖注入 | **不改 10 参构造器**：改用 `@Autowired(required = false)` 字段注入 `KnowledgeService` + `ReportRagProperties`（或 `ObjectProvider` 延迟取）。这样 `ReportWorkflowPartitionTest` 两处 `new ...(null×10)` 保持编译通过；未注入时 `loadCriteriaContext()` 直接返回「未启用」 |
| 5 | `ReportWorkflowService.java` 新增 `loadCriteriaContext(WorkflowState state)` | 见 §4 骨架。`state.context.put("criteriaContext", 文本)` + `put("criteriaSnapshot", List<Map>)`，两者都随 checkpoint 落库 |
| 6 | `ReportWorkflowService.java:588-645` `step6Synthesize` | 读 `criteriaContext` 拼进 `fullPrompt`（`:602-637` 的 `.formatted(...)` 已有 6 个 `%s`，**新增占位符必须同步加实参，错位会静默串段**）；输出要求增第 9 条（见 §5） |

配置同步：`bank-warning-app/src/main/resources/application.yml.example` 的 `app.ai.rag` 段加 3 个键，
并注明「`application.yml` 被 gitignore，换机复制或用 `RAG_*` 环境变量」。

**不需要**：任何前端改动、任何 migration / V17、任何 `chatStream.ts` 改动、`AgentScopeConfig` 改动。

---

## 4. `loadCriteriaContext` 骨架（关键逻辑）

```java
private static final String FALLBACK_NO_KB = "知识库未提供判定口径：本次不得编造阈值或指标定义。";

private String loadCriteriaContext(WorkflowState state) {
    if (knowledgeService == null || reportProps == null
            || !reportProps.reportRetrievalEnabled()) {
        return "知识库参考未启用（app.ai.rag.report-enabled=false）。";
    }
    AiCaller caller = state.caller != null
            ? state.caller
            : AiCaller.of(SecurityUtils.getDataScope());   // 与 toolContext():393-397 同款兜底
    String traceId = "report-" + state.context.getOrDefault("runId", "unknown") + "-step6";
    try {
        List<Document> docs = knowledgeService.retrieve(
                reportProps.reportQuery(), KnowledgeService.Scenario.REPORT,
                caller, reportProps.reportTopK(), traceId);
        if (docs.isEmpty()) {
            return FALLBACK_NO_KB;
        }
        List<Map<String, Object>> snapshot = new ArrayList<>();
        StringBuilder sb = new StringBuilder("以下内容来自平台知识库，按相关度排序：\n");
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            String docId = String.valueOf(d.getMetadata().getDocId());
            String chunkId = String.valueOf(d.getId());
            String text = String.valueOf(d.getMetadata().getContent());
            Double score = d.getScore() instanceof Number n ? n.doubleValue() : null;
            sb.append("[").append(i + 1).append("] 来源 ").append(docId)
              .append("/").append(chunkId).append("\n").append(text).append("\n\n");
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("docId", docId);
            hit.put("chunkId", chunkId);
            hit.put("score", score);          // 允许 null，不用 Map.of（会 NPE）
            snapshot.add(hit);
        }
        state.context.put("criteriaSnapshot", snapshot);   // 纯 Map/List，可序列化
        state.context.put("criteriaTraceId", traceId);
        return sb.toString();
    } catch (KnowledgeUnavailableException e) {
        log.warn("[report-rag] 知识库不可用（{}）: {}", e.reason(), e.getMessage());
        // 不抛：任一步抛异常会让整份报告 FAILED（:246-253）
        return "知识库不可用（" + e.reason() + "）：" + e.getMessage()
                + "。本次不得编造判定依据，请在报告中标注该限制。";
    }
}
```

要点：

- `runId` 在 `executeTaskReport():189` 生成但未进 `state.context`；本次顺手 `state.context.put("runId", runId)`，
  让 `ai_rag_traces.trace_id` 能与 `ai_report_runs.run_id` 对上，报告留痕可按一次运行串起来。
- `score` 可能为 `null`（`Document#getScore()` 返回 `Object`），必须用 `LinkedHashMap` 逐项 put，`Map.of` 遇 null 会 NPE。
- `retrieve()` 内部已写留痕，成功/失败都有 status；报告侧不需要重复记录。

---

## 5. Step 6 提示词契约

在 `:602` 的 `=== 按经纬度分区汇总 ===` 之后、`=== 输出要求 ===` 之前插入：

```
=== 规范口径（知识库，系统自动检索） ===
%s
```

在「输出要求」追加第 9 条：

```
9. 「指标分析」与「风险评估」两章中，凡涉及风险等级阈值、指标定义、权重、
   等级划分与判定依据的表述，只能引用上面「规范口径（知识库）」一节的内容，
   并在该句末尾标注 [n] 对应序号；该节未覆盖的内容写「知识库未提供」，
   不得依据自身记忆补写数字或标准编号。若该节提示知识库不可用或未启用，
   必须在报告「概述」中单列一句说明本次判定依据受限。
   其余章节（断面数量、里程、天气、图表、分区边界）仍以 Step 1~5 的数据为准，
   不受本节约束。
```

最后一句很重要：避免模型把知识库文本当成数据源，去改写**断面数量 / 分区经纬度**这类
只能来自 `tiles[].bbox` 与 `riskDataText` 的确定性数字（分区契约不受影响）。

---

## 6. 灰度、可复现性与验收

**开关与前置**

| 阶段 | 动作 |
|---|---|
| 现在 | `report-enabled=false` 提交代码；行为与今天完全一致，零回归风险 |
| 阶段 3 之后 | 灌入可从代码验证的平台冻结口径（`template.json` / `riskScoring.ts` / `riskLevel.py` 的 12 组参数）且标 `is_public=true`，再把开关打开 |
| 打开前 | 必须确认知识库里没有 `KNOWLEDGE.md` 计算章节、没有 `report_*.md`（用户已拍板：AI 生成报告一律不入库） |

**可复现性定义变了**：从「同输入 → 同输出」变成「**同输入 + 同语料版本 → 同输出**」。
`criteriaSnapshot`（docId / chunkId / score）随 `context_json` 落库，半年后翻旧报告能解释它依据哪一版口径。

**验收**

1. 开关 false：跑一次报告，正文与改造前逐字一致（回归基线）。
2. 开关 true + 库里有口径：报告「指标分析」里的阈值与 `riskScoring.ts` 一致，句末有 `[n]`。
3. 开关 true + 断网 / 清 key：报告**仍生成**，正文出现「知识库不可用」说明，`ai_rag_traces` 有一行
   `scenario=REPORT` 且 `status=EMBED_FAILED|UNCONFIGURED`，不是 `OK`。
4. 普通用户身份触发：留痕 `visible < hits` 能验证权限裁剪生效（管理员 `seeAll()` 不过滤）。
5. `mvn -pl bank-warning-ai -am test`：现有 230 例全绿（重点是 `ReportWorkflowPartitionTest` 两处
   `new ...(null×10)` 不被破坏），新增用例覆盖 1/2/3/4。

---

## 7. 与阶段计划的关系

原属阶段 7，但因改动只落在 2 个业务文件 + 1 个枚举 + 1 个配置类、且零前端零 schema，
可以**提前**为独立一步，排在「4A 建考卷测基线 → 阶段 3 最小灌库」之后，单独 commit。

考卷相应加 **C 类：报告口径一致性题**（报告里的阈值数字是否等于冻结口径、是否标了 `[n]`）。
这类题要调 LLM、结果非确定，**只能抽样人工评审，不进 CI 硬门禁**——报告轨接入后评测盲区缩小但不消失。

---

## 8. 不做的事（明确边界）

- 不做「可点击引用 / 来源面板」（那才需要前后端同改，且要动查看器）。
- 不做报告回灌知识库：`report_*.md` 是 AI 产物，永久黑名单，本报告只**消费**知识库、不**写入**。
- 不把 `query_knowledge` 注册进 Step 6 的 Toolkit。
- 不因本次改动重算任何向量（库内 4 段实测即 `text-embedding-v4`，无错配）。
- 不新增 migration；`migration/V*.sql` 只留档、不在 Flyway 内（项目既有约定）。
