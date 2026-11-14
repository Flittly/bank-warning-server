# RAG 权威语料准入清单

> 新建的活文档，不是历史测量快照。阶段 4 收尾后，唯一能解开「检索指标失真」的动作是补语料，本文规定补哪些、不补哪些、传完怎么验。
> 数字口径截至 2026-11-14：`ai_knowledge_store` 4 行 / 1 个 `doc_id`（`BMS.docx`）/ trigram 索引 0 根 / `ai_rag_traces` 0 行。

## 1. 结论

阶段 3 的「可重复执行灌库脚本」已被前端上传链路取代，不需要写脚本：

```
选文件 → POST /knowledge/upload（PDF/DOCX）或 POST /knowledge（文本）
       → chunkText() 按空行分段 → addDocuments() 自动 embedding → 写 ai_knowledge_store
       → 补写 user_id / is_public
```

所以现在该做的不是设计检索算法，而是**圈定哪几份算权威资料，然后从知识中心页上传**。

## 2. 准入清单（待你 / 领域老师圈定）

### 2.1 建议入库（代码即事实，平台冻结口径）

| 文件 | 仓库真实路径 | 支撑题目 | 判定 |
|---|---|---|---|
| `template.json` | `bank-model-server/resource/json/Mzs/2023/standard/RiskLevel/template.json` | D01、D02 | 模型实际读取的阈值/权重配置；实查含 `Zb:[20,30,40]`、`wNM/wRE/wGE/wRL`，13 行 360 B，天然 1 段 |
| `riskLevel.py` | `bank-model-server/modelResource/multipleIndicators/riskLevel.py` | D03（标注待复核，见 §3） | 风险等级计算主流程，671 行 26 KB，必须切分后入库 |
| `slopeProtection.py` | `bank-model-server/modelResource/multipleIndicators/slopeProtection.py` | D03 真正事实源 | `compute_slope_protection()` 对非法 PL 抛 `ValueError` |
| `loadControl.py` | `bank-model-server/modelResource/multipleIndicators/loadControl.py` | D03 真正事实源 | `compute_load_control()` 对非法 LC 抛 `ValueError` |
| `BMS.docx` | 已在库 | D04 | 4 段、已标公共；含 `Dsed=hs/hc=42/13.5=3.1` |

最小集 = `template.json` + `riskLevel.py`（2 份）。加上两个枚举文件后 `doc_id` 达 5 个，正好触到阶段 5 的开工门槛。

### 2.2 明确禁止入库

| 文件 | 路径 | 理由 |
|---|---|---|
| `KNOWLEDGE.md` | `bank-warning-server/.agentscope/workspace/chat/knowledge/KNOWLEDGE.md` | 与平台冻结口径冲突，入库等于主动灌错误知识 |

实查冲突点：

- 它写「1级低风险 … 4级高风险」，平台真源 `RISK_LABELS` 是 `risk_level` 0=低 / 1=中 / 2=较高 / 3=高，序号方向都相反；
- 它写「水位波动幅度超过 3m 时为高风险」「Zb > 5m 为高风险」，而 `template.json` 里 `Zd:[0.1,0.15,0.3]`、`Zb:[20,30,40]` 是多指标分级阈值，不是单条件判定；
- **考卷 A04「Zd 超过 3m 就算高风险，对吗」正是一道该拒答对抗题**，说法来源就是这类口径。把它传进知识库，A04 会从「该拒答」变成「有据可依地答错」。

另：此前定过「AI 生成的报告统一不入知识库」，本条不变。

### 2.3 缺失、且不要伪造

`GB 50286` 规范原文全仓没有。A05 期望空返回是对的，**保持缺失**。将来若拿到规范原文，需同时改考卷（把 A05 从该拒答题改成可回答题）并复跑基线，不能只传文件不改题。

## 3. 三处口径校正（此前口头表述有误，以此为准）

1. **D 类期望文档只有 3 个**：`template.json`（D01/D02）、`riskLevel.py`（D03）、`BMS.docx`（D04）。其中 `BMS.docx` **已在库**，`KNOWLEDGE.md` **不是任何 D 类题的期望文档**（它只在 D02 的备注里被提到有代号对调问题）。此前说「D 类期望文档全 0 / 全不在库」不准确。
2. **D03 的期望文档标注需复核**：`riskLevel.py:60` 唯一的 `ValueError` 是「无可用水动力节点」，与 PL/LC 无关；真正对非法枚举抛 `ValueError` 的是 `slopeProtection.py` / `loadControl.py`。**本轮不擅自改考卷**（会破坏 4A 基线可比性），只在此登记；等语料入库后由你决定是改 `expectedDocIds` 还是补传两个枚举文件。
3. **前端有知识库检索入口**：`knowledgeApi.ts:52 searchKnowledge()` + `KnowledgePage.tsx`「检索测试」区会显示每条命中的相似度。上传后无需新页面即可人工验收。

## 4. 上传操作（可照做）

1. 打开知识中心页 → 勾选顶部「**公开**」→ 点「上传文档」。
   - **必须勾公开**：权威资料要被所有登录用户检索到；评测 CLI 的普通用户视角是 `forUser(7L)`，私有文档在那种视角下不可见。
2. 选择 §2.1 的文件（前端 `accept` 已放开 `.json` / `.py`）。
3. 分类弹窗填 `hydraulic` / `engineering` 等，**不要改标题**：后端用文件名当 `docId`，考卷 `expectedDocIds` 也是文件名，改了就对不上。
4. 传完在「检索测试」区搜一句，例如 `Zb 的三级阈值 20 30 40`，看返回的 `docId` 与相似度。

## 5. 上传后验收

```bash
cd bank-warning-server
mvn -q -pl bank-warning-ai -am test
mvn -q -pl bank-warning-ai org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
  -Dexec.classpathScope=test -Dexec.mainClass=com.yangtze.bankwarning.ai.rag.RagEvalCli \
  -Dexec.args="--jdbc --out target/rag-eval-4B.md"
```

判据：① `ai_knowledge_store` 的 `doc_id` ≥ 5；② D01/D02 能命中 `template.json`；③ 报告里 `异常题数 = 0`、全口径 Recall/Precision 不与 4A 基线冲突；④ 把 D01~D04 的 `scored` 从 `false` 放开、复跑一次，才是真正的 4B。

## 6. 为什么本轮不放开 D 类计分

放开计分的前提是「期望文档已在库」。现在 2 份缺失、D03 期望文档还标错，放开只会得到 4 道 0 分并污染基线可比性。**等 §4 传完再放开**，本轮只把上传口子准备好。

## 7. 本轮为上传链路做的改动（不涉及检索算法）

| 位置 | 改动 |
|---|---|
| `KnowledgeController.importKnowledge()` | 文本直传改用 `chunkText()` 分段，与 PDF 路径同构；补 `chunkIndex` / `totalChunks`；空标题/空内容返回明确错误；返回 `chunks` 数 |
| `knowledgeApi.ts` | 两个上传函数透传 `isPublic`，统一 `UploadResult`，文本路径检查 `success === false` |
| `KnowledgePage.tsx` | `accept` 加 `.json,.py`；顶部加「公开」勾选 |

`KnowledgeService.doRetrieve()` 一字未动，阶段 5 仍为未开工状态。
