"""Extract a PDF into md-lite/1 text using Docling.

Usage: uv run python extract_text_docling.py <path-to-pdf>
Output: md-lite/1 text to stdout; 失败原因与统计写 stderr。

为什么 PDF 需要这一条路
-----------------------
pypdf 只按文本流取字，拿到的是「一串字符」。PDF 里没有段落、没有表格、
没有阅读顺序——双栏排版会被读成交错的两列，表格会被拆成散落的数字，
扫描件干脆一个字都取不到。Docling 用版面模型恢复版面与阅读顺序，
用 TableFormer 恢复表格结构，把结果导出成 markdown。

它和 docx 那条路的产出必须**同一种格式**（md-lite/1），否则下游的切块器、
引用编号、评测口径会三处分叉。

依赖与离线
----------
Docling 的模型只在 PDF 管线里用，docx 不需要（官方 FAQ：Other document
types (docx, pptx, etc) do not have any such requirement）。

模型**不需要 key，也不需要 url**：它下载到本机的权重文件，由 torch 在本进程内
加载。需要配的只是「放在哪个目录」：

    DOCLING_ARTIFACTS_PATH   模型目录；不设 = 首次转换自动联网下载

离线/内网部署必须预设这个变量，并且把模型目录一起搬过去。**没有模型时本脚本
会以非 0 退出，由 Java 侧回落到 pypdf 并标记 degraded，不会让上传失败。**

安装（只在本机启用时执行一次）：

    uv pip install --project <skill-dir> "docling-slim[format-pdf,models-local]"

`format-pdf` 不含 torch，`models-local` 才带——两者都要，因为版面与表格模型
吃的是 PyTorch 权重。
"""

import os
import sys

PAGE_PLACEHOLDER = "<!-- page: {page} -->"

# 首行格式声明：切块器靠它区分结构化文本与纯文本（见 MdLiteChunker.FORMAT_MARKER）。
FORMAT_MARKER = "<!-- md-lite/1 -->"


def build_converter():
    """构造 PDF 转换器；Docling 缺失时抛 ImportError 由调用方翻译成退出码。"""
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.pipeline_options import PdfPipelineOptions
    from docling.document_converter import DocumentConverter, PdfFormatOption

    options = PdfPipelineOptions()
    # OCR 默认开：扫描件没有文本层，关掉就只剩一张白纸。
    # 纯文本 PDF 上它只是多花时间，需要时可用 DOCLING_OCR=0 关掉这一项。
    options.do_ocr = os.environ.get("DOCLING_OCR", "1") != "0"
    # 表格结构是这条路的主要收益，固定开启，不做开关。
    options.do_table_structure = True

    artifacts = os.environ.get("DOCLING_ARTIFACTS_PATH")
    if artifacts:
        # 只有这一行是"配置"：指向本地模型目录。不设则首次从 HuggingFace 拉取。
        options.artifacts_path = artifacts

    return DocumentConverter(
        format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=options)}
    )


def export_markdown(document):
    """导出 markdown，尽量带页边界标记。

    页号是 payload 的一部分（引用能写到「第 3 页」）。`page_break_placeholder`
    在不同版本上参数名/格式占位符不完全一致，所以这里不赌：优先带页标记，
    不支持就退化成不带页标记的纯 markdown——宁可少一个页码，也不能整个转换失败。
    """
    try:
        markdown = document.export_to_markdown(page_break_placeholder=PAGE_PLACEHOLDER)
    except Exception:
        markdown = document.export_to_markdown()
    # 占位符没被替换时会把 `{page}` 原样留下，这种行没有任何信息，删掉
    if "{page}" in markdown:
        markdown = "\n".join(
            line for line in markdown.splitlines() if "{page}" not in line
        )
    return markdown


def main():
    if len(sys.argv) < 2:
        print("Usage: extract_text_docling.py <pdf-path>", file=sys.stderr)
        sys.exit(1)

    path = sys.argv[1]

    try:
        converter = build_converter()
    except ImportError as exc:
        print(
            "[docling] 未安装或缺少 PDF 组件（%s）。"
            "安装：uv pip install --project <skill-dir> "
            "\"docling-slim[format-pdf,models-local]\"" % exc,
            file=sys.stderr,
        )
        sys.exit(2)

    try:
        result = converter.convert(path)
    except Exception as exc:
        # 模型缺失、离线且没配 artifacts、加密 PDF 都落在这里：
        # 一律非 0 退出，交给 Java 侧回落到 pypdf 并标记 degraded。
        print("[docling] 转换失败: %s: %s" % (type(exc).__name__, exc), file=sys.stderr)
        sys.exit(3)

    markdown = export_markdown(result.document)
    pages = len(getattr(result.document, "pages", {}) or {})
    table_lines = sum(1 for line in markdown.splitlines() if line.strip().startswith("|"))
    print(
        "[md-lite] %s format=md-lite/1 chars=%d pages=%d table_lines=%d artifacts=%s"
        % (
            path,
            len(markdown),
            pages,
            table_lines,
            os.environ.get("DOCLING_ARTIFACTS_PATH") or "(未设置，走缓存目录)",
        ),
        file=sys.stderr,
    )
    print(FORMAT_MARKER + "\n\n" + markdown)


if __name__ == "__main__":
    main()
