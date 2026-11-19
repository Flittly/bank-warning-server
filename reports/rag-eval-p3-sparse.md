# 关键词路单路对照（阶段 3：中文词项）

- 生成时间：2026-11-19 15:59:22
- 考卷：v3，共 52 题，计分且非拒答 38 题
- 口径：只跑 SparseRetriever（不调 embedding、不联网），topK=5，指标按 docId 计算
- 对照：`锚点only` = 阶段 3 之前的行为（只认 ASCII 标识符与数字）；`+中文词项` = 阶段 3（jieba + tsvector）

## 汇总

| 模式 | 有命中题数 | Recall@5 | MRR |
|---|---|---|---|
| 锚点only（阶段 3 之前） | 17/38 | 0.4474 | 0.3333 |
| +中文词项（阶段 3） | 26/38 | 0.6842 | 0.5219 |

## 分类

| 类别 | 题数 | 锚点only 命中 | 中文 命中 | 锚点only Recall | 中文 Recall | ΔRecall |
|---|---|---|---|---|---|---|
| A | 10 | 1 | 4 | 0.1000 | 0.4000 | +0.3000 |
| B | 14 | 7 | 10 | 0.5000 | 0.7143 | +0.2143 |
| D | 14 | 9 | 12 | 0.6429 | 0.8571 | +0.2143 |

## 逐题

| id | 类别 | 计分 | 期望 | 锚点only 返回 | 中文返回 | 变化 |
|---|---|---|---|---|---|---|
| A01 | A | 是 | BMS.docx |  | 3-项目技术报告.docx | 新增召回 |
| A02 | A | 是 | BMS.docx |  | 3-项目技术报告.docx | 新增召回 |
| A03 | A | 是 | BMS.docx |  | 3-项目技术报告.docx | 新增召回 |
| A04 | A | 否 |  | riskLevel.py,3-项目技术报告.docx,riskScoring.ts | 3-项目技术报告.docx,riskScoring.ts,riskLevel.py | — |
| A05 | A | 否 |  | 3-项目技术报告.docx | 3-项目技术报告.docx | — |
| A13 | A | 否 |  |  | 3-项目技术报告.docx | 新增召回 |
| A14 | A | 否 |  |  |  | 仍为空 |
| A06 | A | 是 | 3-项目技术报告.docx |  | 3-项目技术报告.docx | 新增召回 |
| A07 | A | 是 | 3-项目技术报告.docx |  | 3-项目技术报告.docx | 新增召回 |
| A08 | A | 是 | riskScoring.ts | riskScoring.ts | riskScoring.ts | — |
| A09 | A | 是 | riskLevel.py | riskScoring.ts,BMS.docx | riskScoring.ts,BMS.docx | — |
| A10 | A | 是 | template.json |  | 3-项目技术报告.docx | 新增召回 |
| A11 | A | 是 | BMS.docx |  | 3-项目技术报告.docx | 新增召回 |
| A12 | A | 是 | 3-项目技术报告.docx |  | 3-项目技术报告.docx | 新增召回 |
| B01 | B | 是 | riskScoring.ts |  | 3-项目技术报告.docx,riskScoring.ts | 新增召回 |
| B02 | B | 是 | riskScoring.ts | 3-项目技术报告.docx,riskScoring.ts,riskLevel.py | riskLevel.py,riskScoring.ts | — |
| B03 | B | 是 | riskScoring.ts | riskLevel.py,BMS.docx,riskScoring.ts | riskScoring.ts,riskLevel.py,BMS.docx | — |
| B04 | B | 是 | riskLevel.py |  | 3-项目技术报告.docx | 新增召回 |
| B05 | B | 是 | riskScoring.ts |  | 3-项目技术报告.docx | 新增召回 |
| B06 | B | 是 | riskScoring.ts | riskScoring.ts,riskLevel.py | riskLevel.py,riskScoring.ts | — |
| B07 | B | 是 | riskScoring.ts | template.json,riskScoring.ts,3-项目技术报告.docx | template.json,riskScoring.ts,3-项目技术报告.docx | — |
| B08 | B | 是 | template.json | riskLevel.py,riskScoring.ts | riskLevel.py,riskScoring.ts | — |
| B09 | B | 是 | riskScoring.ts | 3-项目技术报告.docx,riskLevel.py | 3-项目技术报告.docx,riskScoring.ts | — |
| B10 | B | 是 | riskLevel.py | riskScoring.ts,riskLevel.py | riskLevel.py,riskScoring.ts | — |
| B11 | B | 是 | riskLevel.py | riskScoring.ts,BMS.docx | riskScoring.ts,BMS.docx | — |
| B12 | B | 是 | riskScoring.ts | riskLevel.py | riskLevel.py,riskScoring.ts | — |
| B13 | B | 是 | riskLevel.py | riskLevel.py | riskLevel.py,riskScoring.ts | — |
| B14 | B | 是 | riskScoring.ts | riskLevel.py,riskScoring.ts | riskLevel.py,riskScoring.ts | — |
| C01 | C | 否 | 3-项目技术报告.docx,riskScoring.ts | riskScoring.ts,3-项目技术报告.docx | riskScoring.ts,BMS.docx,3-项目技术报告.docx | — |
| C02 | C | 否 | 3-项目技术报告.docx,riskScoring.ts | riskScoring.ts | riskScoring.ts,riskLevel.py | — |
| C03 | C | 否 | 3-项目技术报告.docx,template.json |  | 3-项目技术报告.docx | 新增召回 |
| C04 | C | 否 | 3-项目技术报告.docx,BMS.docx |  | 3-项目技术报告.docx | 新增召回 |
| C05 | C | 否 | 3-项目技术报告.docx,riskScoring.ts |  | riskScoring.ts,3-项目技术报告.docx | 新增召回 |
| C06 | C | 否 | BMS.docx,riskLevel.py | riskScoring.ts,BMS.docx | riskScoring.ts,BMS.docx | — |
| C07 | C | 否 | 3-项目技术报告.docx,riskScoring.ts | 3-项目技术报告.docx,riskLevel.py | 3-项目技术报告.docx,riskScoring.ts,riskLevel.py | — |
| C08 | C | 否 | 3-项目技术报告.docx,riskScoring.ts |  | 3-项目技术报告.docx | 新增召回 |
| C09 | C | 否 | 3-项目技术报告.docx,template.json | riskLevel.py,BMS.docx | riskLevel.py,riskScoring.ts | — |
| C10 | C | 否 | 3-项目技术报告.docx,riskLevel.py | BMS.docx,riskScoring.ts,riskLevel.py | BMS.docx,riskScoring.ts | — |
| D01 | D | 是 | template.json | template.json,riskScoring.ts,3-项目技术报告.docx | template.json,riskScoring.ts,3-项目技术报告.docx | — |
| D02 | D | 是 | template.json | riskScoring.ts,riskLevel.py | riskScoring.ts | — |
| D03 | D | 是 | riskLevel.py | riskScoring.ts | riskScoring.ts,riskLevel.py | — |
| D04 | D | 是 | BMS.docx | BMS.docx,riskLevel.py,3-项目技术报告.docx | BMS.docx,3-项目技术报告.docx,riskLevel.py | — |
| D05 | D | 是 | riskScoring.ts |  | 3-项目技术报告.docx,riskScoring.ts | 新增召回 |
| D06 | D | 是 | riskLevel.py | 3-项目技术报告.docx,BMS.docx,riskLevel.py,template.json | BMS.docx,3-项目技术报告.docx,riskLevel.py | — |
| D07 | D | 是 | template.json | riskScoring.ts,template.json,3-项目技术报告.docx | riskScoring.ts,template.json,3-项目技术报告.docx | — |
| D08 | D | 是 | template.json | template.json,3-项目技术报告.docx,riskScoring.ts | template.json,3-项目技术报告.docx,riskScoring.ts | — |
| D09 | D | 是 | riskScoring.ts | riskScoring.ts,riskLevel.py | riskScoring.ts,riskLevel.py | — |
| D10 | D | 是 | riskLevel.py | riskLevel.py | riskLevel.py | — |
| D11 | D | 是 | template.json | riskLevel.py,BMS.docx,riskScoring.ts | riskScoring.ts,BMS.docx,riskLevel.py | — |
| D12 | D | 是 | 3-项目技术报告.docx |  | 3-项目技术报告.docx | 新增召回 |
| D13 | D | 是 | riskScoring.ts | 3-项目技术报告.docx,riskScoring.ts,riskLevel.py | riskLevel.py,riskScoring.ts | — |
| D14 | D | 是 | riskLevel.py | riskLevel.py | riskLevel.py,riskScoring.ts | — |
