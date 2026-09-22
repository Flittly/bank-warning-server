---
name: word
description: Use this skill whenever the user wants to read or extract text from a Word document (.docx). Provides plain-text extraction from a .docx file, preserving paragraph order and dropping empty paragraphs. If the user mentions a .docx file, uploads a Word document to the knowledge base, or asks to read a Word document's content, use this skill.
version: 0.1.0
output: text
---

# Word Processing Guide

## Overview

本 skill 提供 `.docx`（Word 文档）的纯文本抽取能力，用于把上传的 Word 文档导入知识库。

## 可用脚本

| 脚本 | 用途 | 调用参数 |
|---|---|---|
| `scripts/extract_text_docx.py` | 抽取全文纯文本，按段落顺序拼接，空段落丢弃，结果打到 stdout | 一个 `.docx` 文件的绝对路径 |

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
无需手动安装。
