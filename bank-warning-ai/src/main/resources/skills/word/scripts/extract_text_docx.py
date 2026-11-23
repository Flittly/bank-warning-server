"""Extract a Word document into md-lite/1 text.

Usage: uv run python extract_text_docx.py <path-to-docx>
Output: md-lite/1 text to stdout, one block per blank-line separated group.

为什么不再只输出纯文本
----------------------
旧实现只遍历 `doc.paragraphs`，`doc.tables` 一次都没读。Word 里的表格是
`w:tbl` 节点，不在 paragraphs 里 —— 于是题注留下了、表本身消失了，读起来像
「后面忘了写」。同一份 docx 实测：147 段 / 7 张表 61 行，表格占全文 14.9%
的字符，一个字都没入库。

这个脚本按 OOXML 的 body 顺序遍历 `w:p` / `w:tbl` / `w:sdt`，把结构写成
md-lite/1。四个元素，够用就好：

    标题        `#` × 层级 + 空格 + 原文标题
    表格题注    `> 表 4-1 平台分层技术选型一览`（与表同属一块，中间不空行）
    表格        `| 甲 | 乙 |` + `|---|---|` 分隔行
    页边界      `<!-- page: N -->`（docx 无页概念，本脚本不产生；PDF 提取器产生）

下游 `MdLiteChunker` 按这些标记切块，并把标记剥离成 payload —— 标记本身不进
`content`，否则 `<!-- page: 3 -->` 会被向量化和关键词匹配当成正文。

标题层级怎么判
--------------
三级证据，从严到宽：

1. `w:pPr/w:outlineLvl`（val 是 0 基，0 = 一级标题）；
2. `w:pStyle` 指向的样式名含 heading / 标题，级别从其数字取；
3. 字面编号（`一、` / `（一）` / `4.1.1`）——**兜底**，因为很多文档的编号是
   手打的字而不是自动编号，样式全是 Normal，前两级证据一条都没有。

第 3 条是刻意的：Docling 的 docx 后端只认前两级，遇到这种文档会把每个段落
都判成 Normal，层级全丢。多这一层正则，恰好是它拿不到的信息。
"""

import re
import sys

from docx import Document
from docx.oxml.ns import qn

FORMAT = "md-lite/1"

# 首行格式声明。切块器靠它区分「提取器产出的结构化文本」与「纯文本」——
# 知识库里有 riskLevel.py 这类代码语料，注释就是 `# xxx`，只看 # 行会把它当标题剥掉。
FORMAT_MARKER = "<!-- md-lite/1 -->"

# 标题文本的合理上限：超过这个长度的多半是正文里恰好以编号开头的一句话。
MAX_HEADING_CHARS = 80

# 句末标点：标题不以它们结尾。
SENTENCE_ENDS = "。！？；!?;"

# 中文数字（支持到「百」，够覆盖一级标题）
CN_DIGITS = "零一二三四五六七八九十百"

# 「一、」「二、」 → 一级
RE_CN_L1 = re.compile(r"^\s*([%s]+)\s*[、．.]\s*" % CN_DIGITS)
# 「（一）」「(一)」 → 二级
RE_CN_L2 = re.compile(r"^\s*[（(]\s*([%s]+)\s*[）)]\s*" % CN_DIGITS)
# 「4.1」「4.1.1」 → 级别 = 层级数（4.1 = 2 级，4.1.1 = 3 级）
RE_DOTTED = re.compile(r"^\s*(\d+(?:\.\d+)+)\.?(?=\s|\D|$)")

# 样式名里出现这些词就当作标题样式
HEADING_STYLE_WORDS = ("heading", "标题", "title")


def build_style_names(doc):
    """styleId → 样式显示名。

    中文版 Word 常把 Heading 1 的 styleId 存成 "1"、"2" 这种纯数字，光看
    styleId 分辨不出是不是标题，必须回 styles.xml 取 `w:name`。
    """
    names = {}
    try:
        styles = doc.styles.element
    except Exception:
        return names
    for st in styles.findall(qn("w:style")):
        sid = st.get(qn("w:styleId"))
        name_el = st.find(qn("w:name"))
        if sid and name_el is not None:
            names[sid] = (name_el.get(qn("w:val")) or "").strip()
    return names


def style_heading_level(paragraph, style_names):
    """按 outlineLvl / pStyle 判标题级别；判不出返回 None。"""
    p_pr = paragraph.find(qn("w:pPr"))
    if p_pr is None:
        return None

    # 证据 1：outlineLvl（0 基）
    lvl_el = p_pr.find(qn("w:outlineLvl"))
    if lvl_el is not None:
        try:
            lvl = int(lvl_el.get(qn("w:val")))
            if 0 <= lvl <= 8:
                return lvl + 1
        except (TypeError, ValueError):
            pass

    # 证据 2：段落样式名
    style_el = p_pr.find(qn("w:pStyle"))
    if style_el is None:
        return None
    style_id = style_el.get(qn("w:val")) or ""
    # 先看 styleId 本身，再看 styles.xml 里的显示名（中文 Word 的坑）
    for probe in (style_id, style_names.get(style_id, "")):
        low = probe.lower()
        if not any(word in low for word in HEADING_STYLE_WORDS):
            continue
        m = re.search(r"(\d+)", probe)
        return int(m.group(1)) if m else 1
    return None


def literal_heading_level(text):
    """按字面编号推层级；不是编号开头返回 None。

    只在前两级证据都缺失时使用。约束三道，避免把正文里「2026 年…」「3.5 倍」
    这类句子误判成标题：长度上限、不以句末标点结尾、编号后必须有内容。
    """
    if not text or len(text) > MAX_HEADING_CHARS:
        return None
    if text[-1] in SENTENCE_ENDS:
        return None

    m = RE_DOTTED.match(text)
    if m:
        # 「4.1.1」→ 3；同时要求编号后面确实还有字，否则「4.1.1」单独一行更像页码
        rest = text[m.end():].strip()
        if rest:
            return m.group(1).count(".") + 1

    m = RE_CN_L2.match(text)
    if m:
        rest = text[m.end():].strip()
        if rest:
            return 2

    m = RE_CN_L1.match(text)
    if m:
        rest = text[m.end():].strip()
        if rest:
            return 1
    return None


def run_text(node):
    """按顺序拼出一个容器里的可见文本。

    只认 `w:r` 的直接子元素：`w:tabs/w:tab` 是制表位定义，不是制表符，
    用 `iter(qn("w:tab"))` 会把它们也算进来，凭空插入一堆制表符。
    """
    parts = []
    for run in node.iter(qn("w:r")):
        for child in run.iterchildren():
            tag = child.tag
            if tag == qn("w:t"):
                parts.append(child.text or "")
            elif tag == qn("w:tab"):
                parts.append("\t")
            elif tag == qn("w:br"):
                parts.append("\n")
    return "".join(parts)


def paragraph_text(p):
    return run_text(p).strip()


def iter_body_blocks(container):
    """按文档顺序产出 ("p", 元素) / ("tbl", 元素)，递归展开内容控件 sdt。"""
    for child in container.iterchildren():
        tag = child.tag
        if tag == qn("w:p"):
            yield ("p", child)
        elif tag == qn("w:tbl"):
            yield ("tbl", child)
        elif tag == qn("w:sdt"):
            content = child.find(qn("w:sdtContent"))
            if content is not None:
                yield from iter_body_blocks(content)


def raw_table_rows(tbl):
    """取出每行的 (文本, 横向跨度, 是否纵向合并的续行)。"""
    rows = []
    for tr in tbl.findall(qn("w:tr")):
        cells = []
        for tc in tr.findall(qn("w:tc")):
            span = 1
            merged_continuation = False
            tc_pr = tc.find(qn("w:tcPr"))
            if tc_pr is not None:
                gs = tc_pr.find(qn("w:gridSpan"))
                if gs is not None:
                    try:
                        span = max(1, int(gs.get(qn("w:val"))))
                    except (TypeError, ValueError):
                        span = 1
                vm = tc_pr.find(qn("w:vMerge"))
                if vm is not None:
                    # val 缺省即 "continue"（Word 的简写）
                    merged_continuation = (vm.get(qn("w:val")) or "continue") == "continue"
            text = " ".join(
                part.strip() for part in run_text(tc).split("\n") if part.strip()
            )
            cells.append((text, span, merged_continuation))
        rows.append(cells)
    return rows


def expand_grid(raw_rows):
    """把横向/纵向合并还原成等宽二维表。

    纵向合并的续行（vMerge=continue）在 XML 里是空单元格。直接输出会让
    「层次」列从第二行起变成空白，读起来像漏字段 —— 这里沿用上一行同列的值。
    """
    out = []
    previous = {}
    for cells in raw_rows:
        row = []
        col = 0
        for text, span, continuation in cells:
            if continuation:
                text = previous.get(col, "")
            row.append(text)
            for _ in range(span - 1):
                row.append("")
            previous[col] = text
            col += span
        out.append(row)
    return out


def to_markdown_table(rows):
    """二维表 → 管道表（首行当表头，第二行补分隔行）。"""
    rows = [r for r in rows if any(c.strip() for c in r)]
    if not rows:
        return []
    width = max(len(r) for r in rows)
    rows = [r + [""] * (width - len(r)) for r in rows]

    def esc(cell):
        return cell.replace("|", "\\|").replace("\n", " ").strip()

    lines = ["| " + " | ".join(esc(c) for c in rows[0]) + " |"]
    lines.append("|" + "|".join(["---"] * width) + "|")
    for r in rows[1:]:
        lines.append("| " + " | ".join(esc(c) for c in r) + " |")
    return lines


def looks_like_caption(text):
    """「表 4-1 …」「Table 3 …」：以表字开头、含编号、不太长。"""
    if not text or len(text) > 60:
        return False
    head = text.lstrip()
    if not (head.startswith("表") or head.lower().startswith("table")):
        return False
    return bool(re.search(r"\d", head))


def find_caption(blocks, tbl_index, consumed):
    """在表格前后找题注，返回 (索引, 文本)。

    先往上最多看 2 段（题注写在表上方是最常见的），没有再往下看 1 段。
    `consumed` 里已经记过的段落不再重复认领，避免两张相邻表抢同一个题注。
    """
    for back in (1, 2):
        idx = tbl_index - back
        if idx >= 0 and idx not in consumed:
            kind, node = blocks[idx]
            if kind == "p":
                text = paragraph_text(node)
                if looks_like_caption(text):
                    return idx, text
    idx = tbl_index + 1
    if idx < len(blocks) and idx not in consumed:
        kind, node = blocks[idx]
        if kind == "p":
            text = paragraph_text(node)
            if looks_like_caption(text):
                return idx, text
    return None, None


def convert(path):
    doc = Document(path)
    style_names = build_style_names(doc)
    blocks = list(iter_body_blocks(doc.element.body))

    # 先认领题注，避免同一段既当正文又当题注输出两遍
    captions = {}
    consumed = set()
    for i, (kind, _) in enumerate(blocks):
        if kind != "tbl":
            continue
        idx, text = find_caption(blocks, i, consumed)
        if idx is not None:
            captions[i] = text
            consumed.add(idx)

    chunks = []
    for i, (kind, node) in enumerate(blocks):
        if kind == "p":
            if i in consumed:
                continue
            text = paragraph_text(node)
            if not text:
                continue
            level = style_heading_level(node, style_names)
            if level is None:
                level = literal_heading_level(text)
            if level:
                # 标题单独成块：切块时它决定这一段的 headingPath，不该和正文混在一起
                text = " ".join(text.split())
                chunks.append("#" * min(level, 6) + " " + text)
            else:
                chunks.append(text)
            continue

        # 表格
        rows = expand_grid(raw_table_rows(node))
        table_lines = to_markdown_table(rows)
        if not table_lines:
            continue
        caption = captions.get(i)
        # 题注与表用单换行连接（不是空行）：空行会被切块器当成两块，
        # 于是「表 4-1」和它下面那张表被分到不同 chunk，表头失去归属。
        if caption:
            chunks.append("> " + " ".join(caption.split()) + "\n" + "\n".join(table_lines))
        else:
            chunks.append("\n".join(table_lines))

    return FORMAT_MARKER + "\n\n" + "\n\n".join(chunks)


def main():
    if len(sys.argv) < 2:
        print("Usage: extract_text_docx.py <docx-path>", file=sys.stderr)
        sys.exit(1)

    text = convert(sys.argv[1])
    # 统计写 stderr：stdout 是与 Java 侧的契约（只放正文），stderr 便于排查
    headings = sum(1 for line in text.splitlines() if line.startswith("#"))
    tables = sum(1 for line in text.splitlines() if line.startswith("|"))
    print("[md-lite] %s format=%s chars=%d heading_lines=%d table_lines=%d"
          % (sys.argv[1], FORMAT, len(text), headings, tables), file=sys.stderr)
    print(text)


if __name__ == "__main__":
    main()
