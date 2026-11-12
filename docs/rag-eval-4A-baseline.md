# RAG 检索层评测报告

> 用途：改动前后对照 / 回归门禁。**不是领域认证，不是系统准确率，不进知识库。**

## 运行元信息

| 项 | 值 |
|---|---|
| runId | eval-a6034000-9e71-4f4f-8123-2ebd18b1c1cd |
| goldenVersion | 1 |
| topK | 5 |
| caseCount | 13 |
| scoredCount | 6 |
| generatedAt | 2026-11-12T09:19:42.514331200Z |

## 汇总（仅统计 scored=true 且 status=OK 的非拒答题）

| 指标 | 值 |
|---|---|
| 计分题数 | 4 |
| 平均 Recall@k | 1.0000 |
| 平均 Context Precision | 1.0000 |
| 该拒答题通过率 | 0.0000 |
| 只登记不计分题数 | 7 |
| 异常题数 | 0 |

## 逐题明细

| id | 类别 | 计分 | 期望 | 返回 | 命中 | Recall | Precision | 拒答OK | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| A01 | A | 是 | BMS.docx | BMS.docx | BMS.docx | 1.0000 | 1.0000 | — | OK |
| A02 | A | 是 | BMS.docx | BMS.docx | BMS.docx | 1.0000 | 1.0000 | — | OK |
| A03 | A | 是 | BMS.docx | BMS.docx | BMS.docx | 1.0000 | 1.0000 | — | OK |
| A04 | A | 是 | — | BMS.docx | — | — | — | 否 | OK |
| A05 | A | 是 | — | BMS.docx | — | — | — | 否 | OK |
| A06 | A | 是 | BMS.docx | BMS.docx | BMS.docx | 1.0000 | 1.0000 | — | OK |
| B01 | B | 否 | riskScoring.ts | BMS.docx | — | — | — | — | OK |
| B02 | B | 否 | riskScoring.ts | BMS.docx | — | — | — | — | OK |
| B03 | B | 否 | RISK_LABELS | — | — | — | — | — | OK |
| B04 | B | 否 | riskLevel.py | — | — | — | — | — | OK |
| B05 | B | 否 | template.json | BMS.docx | — | — | — | — | OK |
| B06 | B | 否 | riskScoring.ts | BMS.docx | — | — | — | — | OK |
| C01 | C | 否 | — | BMS.docx | — | — | — | — | OK |

## 边界

- 只覆盖 `KnowledgeService.retrieve()` 的检索入口；报告正文口径需打开 report-enabled 后由 C 类题抽样人工评审。
- 返回列表按唯一 docId 去重：同一文档多个 chunk 不重复计入 Precision。
- Recall 只管「检索有没有命中」，不管 LLM 最终答案；检索全命中也可能答错。
- 分数只用于自己跟自己比，禁止对外表述为「准确率 X%」。

## 本次运行补充

- goldenVersion: 1
- retrievalSource: jdbc 真库直连 + text-embedding-v4（trace 关闭，未写 ai_rag_traces）
- topK: 5
- caseCount: 13
- note: 本报告的权威性上限 = 考卷标注的权威性；未经领域老师审定前只能自称『自拟题库』。
