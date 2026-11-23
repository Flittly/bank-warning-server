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

    uv pip install "docling-slim[standard]"

只装 `format-pdf,models-local` 是不够的：那条组合缺 `rtree`（版面后处理的
空间索引）与 `opencv`（版面模型的前处理），实测会依次报
`No module named 'cv2'`、`The 'rtree' package is required`。`standard`
一次带齐 torch、rtree、opencv 与 rapidocr，是能跑通的最小组合。

模型权重不是随包分发的：首次转换由 `huggingface_hub` 拉到 `HF_HOME`
（缺省 `~/.cache/huggingface`），数百 MB 量级。离线机器必须预先拷好这个
目录，并在沙箱里把它加入只读根——否则 docling 加载权重时会被读围栏拦下。
"""

import os
import sys
import tempfile

# docling 的运行期工作区（版面中间产物、临时文件）默认落在 ~/.cache/docling，
# 但沙箱里只有工作目录可写，写家目录会 PermissionError。
# 沙箱已把临时目录指到工作目录，所以顺着 tempfile 走；显式设过的不覆盖。
os.environ.setdefault("DOCLING_CACHE_DIR", os.path.join(tempfile.gettempdir(), "docling"))

PAGE_PLACEHOLDER = "<!-- page: {page} -->"

# docling 只认固定占位符，页码由本脚本按顺序补上（见 export_markdown）
PAGE_BREAK = "<!-- page break -->"

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
    """导出 markdown，并把 docling 的「换页占位符」翻译成带页码的页标记。

    docling 的 `page_break_placeholder` 只接受**固定字符串**，不认 `{page}` 之类的格式位
    （看 `export_to_markdown` 的签名注释：`e.g. "<!-- page break -->"`）。
    早先直接传 `<!-- page: {page} -->` 的结果是：占位符原样留在正文里、`{page}` 永远不替换，
    而清理逻辑又把这一整行删掉——页码一个都没拿到。

    改为：先拿固定占位符，再按出现顺序编号（第 1 个 break 之后是第 2 页）。
    这依赖「PDF 从第 1 页开始、换页处必插占位符」，所以把 break 数和文档页数一起打到
    stderr——两者不等就说明有空白页被跳过，页码会偏，这时才需要换实现。
    """
    try:
        markdown = document.export_to_markdown(
            page_break_placeholder=PAGE_BREAK, escape_html=False)
    except Exception:
        # 版本差异导致参数不被接受时退回默认导出：宁可没有页码，也不能整个转换失败
        markdown = document.export_to_markdown()

    if PAGE_BREAK not in markdown:
        return markdown, 1

    parts = markdown.split(PAGE_BREAK)
    out = [parts[0]]
    for page, part in enumerate(parts[1:], start=2):
        out.append(PAGE_PLACEHOLDER.format(page=page))
        out.append(part)
    return "\n\n".join(p.strip() for p in out if p.strip()), len(parts)


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

    markdown, md_pages = export_markdown(result.document)
    pages = len(getattr(result.document, "pages", {}) or {})
    table_lines = sum(1 for line in markdown.splitlines() if line.strip().startswith("|"))
    print(
        "[md-lite] %s format=md-lite/1 chars=%d pages=%d md_pages=%d table_lines=%d artifacts=%s"
        % (
            path,
            len(markdown),
            pages,
            md_pages,
            table_lines,
            os.environ.get("DOCLING_ARTIFACTS_PATH") or "(未设置，走缓存目录)",
        ),
        file=sys.stderr,
    )
    # 页标记数与文档页数不等 ⇒ 有空白页被跳过，页码会错位，引用里的「第 N 页」就不可信
    if pages and md_pages != pages:
        print(
            "[md-lite] [warn] 页标记 %d 个但文档 %d 页，页码可能错位" % (md_pages, pages),
            file=sys.stderr,
        )
    print(FORMAT_MARKER + "\n\n" + markdown)


if __name__ == "__main__":
    main()
