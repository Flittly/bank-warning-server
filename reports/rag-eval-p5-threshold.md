# RAG 检索层评测报告

> 用途：改动前后对照 / 回归门禁。**不是领域认证，不是系统准确率，不进知识库。**

## 运行元信息

| 项 | 值 |
|---|---|
| runId | eval-76e3eb52-586e-4fce-92ee-d0bd84dc408d |
| goldenVersion | 3 |
| topK | 5 |
| caseCount | 52 |
| scoredCount | 42 |
| generatedAt | 2026-11-19T09:40:48.031115800Z |

## 汇总（仅统计 scored=true 且 status=OK 的非拒答题）

| 指标 | 值 |
|---|---|
| 计分题数 | 38 |
| 平均 Recall@k | 0.8421 |
| 平均 Context Precision | 0.4890 |
| 平均 MRR | 0.7237 |
| 平均 nDCG@k | 0.7547 |
| 该拒答题通过率 | 0.0000 |
| 检索层拒答通过率 | 0.0000 |
| 答案层拒答题数（需 LLM 评审） | 2 |
| 只登记不计分题数 | 10 |
| 异常题数 | 0 |

## 逐题明细

| id | 类别 | 计分 | 拒答层 | top1分 | 期望 | 返回 | 命中 | Recall | Precision | 拒答OK | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A01 | A | 是 | — | 0.1857 | BMS.docx | 3-项目技术报告.docx | — | 0.0000 | 0.0000 | — | OK |
| A02 | A | 是 | — | 0.1505 | BMS.docx | riskScoring.ts, 3-项目技术报告.docx | — | 0.0000 | 0.0000 | — | OK |
| A03 | A | 是 | — | 0.2129 | BMS.docx | 3-项目技术报告.docx | — | 0.0000 | 0.0000 | — | OK |
| A04 | A | 是 | ANSWER | 0.2325 | — | 3-项目技术报告.docx, riskLevel.py | — | — | — | 否 | OK |
| A05 | A | 是 | ANSWER | 0.1896 | — | 3-项目技术报告.docx | — | — | — | 否 | OK |
| A13 | A | 是 | RETRIEVAL | 0.1434 | — | riskLevel.py, 3-项目技术报告.docx | — | — | — | 否 | OK |
| A14 | A | 是 | RETRIEVAL | 0.0061 | — | 3-项目技术报告.docx, riskLevel.py | — | — | — | 否 | OK |
| A06 | A | 是 | — | 0.1961 | 3-项目技术报告.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | 1.0000 | 1.0000 | — | OK |
| A07 | A | 是 | — | 0.2318 | 3-项目技术报告.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | 1.0000 | 1.0000 | — | OK |
| A08 | A | 是 | — | 0.5301 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| A09 | A | 是 | — | 0.4886 | riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| A10 | A | 是 | — | 0.1814 | template.json | riskScoring.ts, riskLevel.py | — | 0.0000 | 0.0000 | — | OK |
| A11 | A | 是 | — | 0.7103 | BMS.docx | 3-项目技术报告.docx | — | 0.0000 | 0.0000 | — | OK |
| A12 | A | 是 | — | 0.1875 | 3-项目技术报告.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | 1.0000 | 1.0000 | — | OK |
| B01 | B | 是 | — | 0.6119 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| B02 | B | 是 | — | 0.1964 | riskScoring.ts | riskScoring.ts, 3-项目技术报告.docx, BMS.docx | riskScoring.ts | 1.0000 | 0.3333 | — | OK |
| B03 | B | 是 | — | 0.1583 | riskScoring.ts | riskLevel.py, riskScoring.ts | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B04 | B | 是 | — | 0.1829 | riskLevel.py | riskScoring.ts, BMS.docx | — | 0.0000 | 0.0000 | — | OK |
| B05 | B | 是 | — | 0.3341 | riskScoring.ts | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B06 | B | 是 | — | 0.6999 | riskScoring.ts | riskLevel.py, riskScoring.ts | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B07 | B | 是 | — | 0.1820 | riskScoring.ts | riskScoring.ts, template.json, riskLevel.py | riskScoring.ts | 1.0000 | 0.3333 | — | OK |
| B08 | B | 是 | — | 0.3014 | template.json | riskScoring.ts, template.json | template.json | 1.0000 | 0.5000 | — | OK |
| B09 | B | 是 | — | 0.5529 | riskScoring.ts | riskScoring.ts, 3-项目技术报告.docx | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B10 | B | 是 | — | 0.5700 | riskLevel.py | riskLevel.py | riskLevel.py | 1.0000 | 1.0000 | — | OK |
| B11 | B | 是 | — | 0.1981 | riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| B12 | B | 是 | — | 0.4140 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B13 | B | 是 | — | 0.7528 | riskLevel.py | riskLevel.py, riskScoring.ts | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| B14 | B | 是 | — | 0.2593 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| C01 | C | 否 | — | 0.1655 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts, 3-项目技术报告.docx | 3-项目技术报告.docx, riskScoring.ts | — | — | — | OK |
| C02 | C | 否 | — | 0.3267 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | — | — | — | OK |
| C03 | C | 否 | — | 0.1532 | 3-项目技术报告.docx, template.json | riskLevel.py, riskScoring.ts | — | — | — | — | OK |
| C04 | C | 否 | — | 0.3007 | 3-项目技术报告.docx, BMS.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | — | — | — | OK |
| C05 | C | 否 | — | 0.2648 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts, 3-项目技术报告.docx | 3-项目技术报告.docx, riskScoring.ts | — | — | — | OK |
| C06 | C | 否 | — | 0.2600 | BMS.docx, riskLevel.py | riskScoring.ts | — | — | — | — | OK |
| C07 | C | 否 | — | 0.1745 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | — | — | — | OK |
| C08 | C | 否 | — | 0.1801 | 3-项目技术报告.docx, riskScoring.ts | 3-项目技术报告.docx, BMS.docx, riskScoring.ts | 3-项目技术报告.docx, riskScoring.ts | — | — | — | OK |
| C09 | C | 否 | — | 0.1832 | 3-项目技术报告.docx, template.json | template.json, riskLevel.py, riskScoring.ts | template.json | — | — | — | OK |
| C10 | C | 否 | — | 0.1881 | 3-项目技术报告.docx, riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | — | — | — | OK |
| D01 | D | 是 | — | 0.1871 | template.json | template.json, riskScoring.ts, 3-项目技术报告.docx | template.json | 1.0000 | 0.3333 | — | OK |
| D02 | D | 是 | — | 0.1625 | template.json | template.json, riskScoring.ts | template.json | 1.0000 | 0.5000 | — | OK |
| D03 | D | 是 | — | 0.4482 | riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| D04 | D | 是 | — | 0.3776 | BMS.docx | BMS.docx, riskLevel.py, 3-项目技术报告.docx | BMS.docx | 1.0000 | 0.3333 | — | OK |
| D05 | D | 是 | — | 0.1787 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| D06 | D | 是 | — | 0.2123 | riskLevel.py | riskLevel.py, BMS.docx, template.json, riskScoring.ts | riskLevel.py | 1.0000 | 0.2500 | — | OK |
| D07 | D | 是 | — | 0.4207 | template.json | riskScoring.ts, template.json, 3-项目技术报告.docx | template.json | 1.0000 | 0.3333 | — | OK |
| D08 | D | 是 | — | 0.1939 | template.json | template.json, 3-项目技术报告.docx, riskScoring.ts | template.json | 1.0000 | 0.3333 | — | OK |
| D09 | D | 是 | — | 0.4071 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| D10 | D | 是 | — | 0.3981 | riskLevel.py | riskLevel.py | riskLevel.py | 1.0000 | 1.0000 | — | OK |
| D11 | D | 是 | — | 0.1746 | template.json | riskScoring.ts, template.json, riskLevel.py | template.json | 1.0000 | 0.3333 | — | OK |
| D12 | D | 是 | — | 0.3076 | 3-项目技术报告.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | 1.0000 | 1.0000 | — | OK |
| D13 | D | 是 | — | 0.3076 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| D14 | D | 是 | — | 0.6140 | riskLevel.py | riskLevel.py, riskScoring.ts | riskLevel.py | 1.0000 | 0.5000 | — | OK |

## 边界

- 只覆盖 `KnowledgeService.retrieve()` 的检索入口；报告正文口径需打开 report-enabled 后由 C 类题抽样人工评审。
- 返回列表按唯一 docId 去重：同一文档多个 chunk 不重复计入 Precision。
- Recall 只管「检索有没有命中」，不管 LLM 最终答案；检索全命中也可能答错。
- 分数只用于自己跟自己比，禁止对外表述为「准确率 X%」。
- `refusalLayer=ANSWER` 的题（如 A04）是「资料存在但说法不被支持」，应由生成层拒答，检索层返回相关片段不算错；它仍计入全口径拒答率以保持与 4A 基线可比，但不作为调检索阈值的依据。
- `top1分` 是返回结果中第 1 个 chunk 的相似度分数（PgVectorStore 把余弦距离换算后的值）；REST / self-check 数据源不提供分数时显示 —。

## 本次运行补充

- goldenVersion: 3
- retrievalSource: jdbc 真库直连 + text-embedding-v4（trace 关闭，未写 ai_rag_traces，hybrid=on, w=1.0/1.0, rerank=on）
- topK: 5
- caseCount: 52
- note: 本报告的权威性上限 = 考卷标注的权威性；未经领域老师审定前只能自称『自拟题库』。
