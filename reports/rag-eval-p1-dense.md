# RAG 检索层评测报告

> 用途：改动前后对照 / 回归门禁。**不是领域认证，不是系统准确率，不进知识库。**

## 运行元信息

| 项 | 值 |
|---|---|
| runId | eval-1423994e-6b6f-42c6-b7ab-cce6d55829dd |
| goldenVersion | 3 |
| topK | 5 |
| caseCount | 52 |
| scoredCount | 42 |
| generatedAt | 2026-11-19T07:24:01.232049400Z |

## 汇总（仅统计 scored=true 且 status=OK 的非拒答题）

| 指标 | 值 |
|---|---|
| 计分题数 | 38 |
| 平均 Recall@k | 0.6842 |
| 平均 Context Precision | 0.4452 |
| 平均 MRR | 0.5570 |
| 平均 nDCG@k | 0.5899 |
| 该拒答题通过率 | 0.2500 |
| 检索层拒答通过率 | 0.5000 |
| 答案层拒答题数（需 LLM 评审） | 2 |
| 只登记不计分题数 | 10 |
| 异常题数 | 0 |

## 逐题明细

| id | 类别 | 计分 | 拒答层 | top1分 | 期望 | 返回 | 命中 | Recall | Precision | 拒答OK | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A01 | A | 是 | — | 0.7119 | BMS.docx | 3-项目技术报告.docx | — | 0.0000 | 0.0000 | — | OK |
| A02 | A | 是 | — | 0.6385 | BMS.docx | riskScoring.ts, riskLevel.py | — | 0.0000 | 0.0000 | — | OK |
| A03 | A | 是 | — | 0.6603 | BMS.docx | 3-项目技术报告.docx | — | 0.0000 | 0.0000 | — | OK |
| A04 | A | 是 | ANSWER | 0.5994 | — | riskLevel.py, riskScoring.ts | — | — | — | 否 | OK |
| A05 | A | 是 | ANSWER | 0.7622 | — | 3-项目技术报告.docx | — | — | — | 否 | OK |
| A13 | A | 是 | RETRIEVAL | 0.4760 | — | riskLevel.py | — | — | — | 否 | OK |
| A14 | A | 是 | RETRIEVAL | — | — | — | — | — | — | 是 | OK |
| A06 | A | 是 | — | 0.5176 | 3-项目技术报告.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | 1.0000 | 1.0000 | — | OK |
| A07 | A | 是 | — | 0.4754 | 3-项目技术报告.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | 1.0000 | 1.0000 | — | OK |
| A08 | A | 是 | — | 0.8469 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| A09 | A | 是 | — | 0.8027 | riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| A10 | A | 是 | — | 0.7754 | template.json | riskScoring.ts, riskLevel.py | — | 0.0000 | 0.0000 | — | OK |
| A11 | A | 是 | — | 0.7860 | BMS.docx | 3-项目技术报告.docx | — | 0.0000 | 0.0000 | — | OK |
| A12 | A | 是 | — | — | 3-项目技术报告.docx | — | — | 0.0000 | 0.0000 | — | OK |
| B01 | B | 是 | — | 0.7409 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| B02 | B | 是 | — | 0.6226 | riskScoring.ts | riskLevel.py, BMS.docx, riskScoring.ts, 3-项目技术报告.docx | riskScoring.ts | 1.0000 | 0.2500 | — | OK |
| B03 | B | 是 | — | 0.5371 | riskScoring.ts | riskLevel.py, riskScoring.ts | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B04 | B | 是 | — | 0.6850 | riskLevel.py | riskScoring.ts | — | 0.0000 | 0.0000 | — | OK |
| B05 | B | 是 | — | 0.7835 | riskScoring.ts | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B06 | B | 是 | — | 0.8243 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B07 | B | 是 | — | 0.7318 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| B08 | B | 是 | — | 0.7357 | template.json | riskLevel.py, riskScoring.ts, template.json | template.json | 1.0000 | 0.3333 | — | OK |
| B09 | B | 是 | — | 0.6659 | riskScoring.ts | riskScoring.ts, riskLevel.py, BMS.docx, 3-项目技术报告.docx | riskScoring.ts | 1.0000 | 0.2500 | — | OK |
| B10 | B | 是 | — | 0.8084 | riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| B11 | B | 是 | — | 0.6974 | riskLevel.py | riskLevel.py | riskLevel.py | 1.0000 | 1.0000 | — | OK |
| B12 | B | 是 | — | 0.7608 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| B13 | B | 是 | — | 0.6596 | riskLevel.py | riskLevel.py, riskScoring.ts | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| B14 | B | 是 | — | 0.7525 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| C01 | C | 否 | — | 0.8453 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts | riskScoring.ts | — | — | — | OK |
| C02 | C | 否 | — | 0.8225 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts | riskScoring.ts | — | — | — | OK |
| C03 | C | 否 | — | 0.6797 | 3-项目技术报告.docx, template.json | riskScoring.ts | — | — | — | — | OK |
| C04 | C | 否 | — | 0.6725 | 3-项目技术报告.docx, BMS.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | — | — | — | OK |
| C05 | C | 否 | — | 0.6860 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts | riskScoring.ts | — | — | — | OK |
| C06 | C | 否 | — | 0.7800 | BMS.docx, riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | — | — | — | OK |
| C07 | C | 否 | — | 0.5198 | 3-项目技术报告.docx, riskScoring.ts | riskScoring.ts, BMS.docx | riskScoring.ts | — | — | — | OK |
| C08 | C | 否 | — | 0.6834 | 3-项目技术报告.docx, riskScoring.ts | 3-项目技术报告.docx, BMS.docx, riskScoring.ts | 3-项目技术报告.docx, riskScoring.ts | — | — | — | OK |
| C09 | C | 否 | — | 0.6571 | 3-项目技术报告.docx, template.json | riskScoring.ts, riskLevel.py | — | — | — | — | OK |
| C10 | C | 否 | — | 0.7622 | 3-项目技术报告.docx, riskLevel.py | riskLevel.py, riskScoring.ts | riskLevel.py | — | — | — | OK |
| D01 | D | 是 | — | 0.6531 | template.json | riskScoring.ts, riskLevel.py | — | 0.0000 | 0.0000 | — | OK |
| D02 | D | 是 | — | 0.6800 | template.json | riskScoring.ts | — | 0.0000 | 0.0000 | — | OK |
| D03 | D | 是 | — | 0.8616 | riskLevel.py | riskScoring.ts, riskLevel.py | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| D04 | D | 是 | — | 0.5458 | BMS.docx | riskLevel.py, BMS.docx, template.json, 3-项目技术报告.docx | BMS.docx | 1.0000 | 0.2500 | — | OK |
| D05 | D | 是 | — | 0.6737 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| D06 | D | 是 | — | 0.6856 | riskLevel.py | 3-项目技术报告.docx, riskLevel.py, BMS.docx | riskLevel.py | 1.0000 | 0.3333 | — | OK |
| D07 | D | 是 | — | 0.6661 | template.json | riskScoring.ts | — | 0.0000 | 0.0000 | — | OK |
| D08 | D | 是 | — | 0.6050 | template.json | riskScoring.ts | — | 0.0000 | 0.0000 | — | OK |
| D09 | D | 是 | — | 0.7520 | riskScoring.ts | riskScoring.ts | riskScoring.ts | 1.0000 | 1.0000 | — | OK |
| D10 | D | 是 | — | 0.7887 | riskLevel.py | riskLevel.py, riskScoring.ts | riskLevel.py | 1.0000 | 0.5000 | — | OK |
| D11 | D | 是 | — | 0.5939 | template.json | riskScoring.ts | — | 0.0000 | 0.0000 | — | OK |
| D12 | D | 是 | — | 0.5237 | 3-项目技术报告.docx | 3-项目技术报告.docx | 3-项目技术报告.docx | 1.0000 | 1.0000 | — | OK |
| D13 | D | 是 | — | 0.6014 | riskScoring.ts | riskScoring.ts, riskLevel.py | riskScoring.ts | 1.0000 | 0.5000 | — | OK |
| D14 | D | 是 | — | 0.7600 | riskLevel.py | riskLevel.py, riskScoring.ts | riskLevel.py | 1.0000 | 0.5000 | — | OK |

## 边界

- 只覆盖 `KnowledgeService.retrieve()` 的检索入口；报告正文口径需打开 report-enabled 后由 C 类题抽样人工评审。
- 返回列表按唯一 docId 去重：同一文档多个 chunk 不重复计入 Precision。
- Recall 只管「检索有没有命中」，不管 LLM 最终答案；检索全命中也可能答错。
- 分数只用于自己跟自己比，禁止对外表述为「准确率 X%」。
- `refusalLayer=ANSWER` 的题（如 A04）是「资料存在但说法不被支持」，应由生成层拒答，检索层返回相关片段不算错；它仍计入全口径拒答率以保持与 4A 基线可比，但不作为调检索阈值的依据。
- `top1分` 是返回结果中第 1 个 chunk 的相似度分数（PgVectorStore 把余弦距离换算后的值）；REST / self-check 数据源不提供分数时显示 —。

## 本次运行补充

- goldenVersion: 3
- retrievalSource: jdbc 真库直连 + text-embedding-v4（trace 关闭，未写 ai_rag_traces，hybrid=off, rerank=off）
- topK: 5
- caseCount: 52
- note: 本报告的权威性上限 = 考卷标注的权威性；未经领域老师审定前只能自称『自拟题库』。
