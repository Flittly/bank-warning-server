---
name: word
description: Use this skill whenever the user wants to read or extract content from a Word document (.docx). Extracts text together with its structure — heading levels, table rows and table captions — as md-lite/1 instead of flattening everything into paragraphs. If the user mentions a .docx file, uploads a Word document to the knowledge base, or asks to read a Word document's content, use this skill.
version: 0.1.0
output: text
---

# Word Processing Guide

## Overview

本 skill 把 `.docx` 抽成 **md-lite/1**，供知识库入库使用。

与旧版的差别只有一件，但它决定了检索能不能命中：**表格不再被丢掉**。
Word 的表格是 `w:tbl` 节点，不在 `doc.paragraphs` 里；旧版只遍历段落，于是
题注留下了、表本身消失了——正文里写着「汇总于表 4-1」，下一行直接是下一个章节标题。
同一份文档实测：7 张表 61 行、占全文 14.9% 的字符，一个字都没入库。

## 输出格式（md-lite/1）

| 元素 | 写法 | 说明 |
|---|---|---|
| 标题 | `## 二、平台总体架构` | `#` 个数 = 层级，供切块器记录章节路径 |
| 表格题注 | `> 表 4-1 平台分层技术选型一览` | 与表同属一块，中间不空行 |
| 表格 | `\| 层次 \| 主要技术栈 \|` + `\|---\|---\|` | 首行表头，第二行分隔行 |
| 页边界 | `<!-- page: N -->` | docx 无页概念，本 skill 不产生 |

标题层级按三级证据判定，从严到宽：`w:outlineLvl` → 样式名含 heading/标题 →
字面编号（`一、` / `（一）` / `4.1.1`）。第三条是兜底：很多文档的编号是手打的字、
样式全是 Normal，只有它能判出层级。

## 可用脚本

| 脚本 | 用途 | 调用参数 |
|---|---|---|
| `scripts/extract_text_docx.py` | 按 body 顺序抽取段落与表格，输出 md-lite/1 到 stdout | 一个 `.docx` 文件的绝对路径 |

## 本环境的调用方式（重要）

**本环境不提供 shell 工具**（`disableShellTool()`），因此上面 `Usage:` 里那种
`uv run python scripts/extract_text_docx.py <path>` 的命令行写法**在本环境无法直接执行**。

所有脚本调用都必须经由工具 `process_pdf`：

```
process_pdf(skillName = "word", scriptName = "extract_text_docx.py", filePath = "<docx 绝对路径>")
```

工具内部会做治理裁决、静态扫描、沙箱执行与输出校验，然后把脚本的 stdout 作为返回值交回给你。
不要尝试自己拼命令行，也不要假设可以读写任意路径——`filePath` 受服务端读取白名单约束。

## 依赖

依赖声明在 `pyproject.toml`（`python-docx`），由沙箱的 `uv run --project` 就地解析，
无需手动安装。本 skill 不加载任何模型，离线可用。

## 改脚本时为什么不升版本号

物化是覆盖写（`Files.write`），同目录下的 `.venv/` 会被保留。升版本号会新建
`.skills-cache/word/<新版本>/`，`uv run --project` 在那里找不到现成环境，必须联网重装依赖
——离线部署的机器会因此让上传直接失败。所以脚本内容变了就原地改，版本号只在
**依赖清单变了**的时候才升。
