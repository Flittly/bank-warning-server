package com.yangtze.bankwarning.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * md-lite/1 的结构感知切块器。
 *
 * <p><b>要解决什么：</b>切块原先只看空行与 500 字上限。提取层一旦把表格写成一行行文本，
 * 这套规则会把一张表当成一段长正文——按句号硬切，切点落在表中间，于是「表 4-1」和
 * 它下面那几行被分进不同块，读到的每一块都是半张表。反过来，如果不切，一张 900 字的表
 * 又会让整块超出嵌入模型的有效范围。
 *
 * <p><b>输入契约（md-lite/1，由 Python 提取器产出）：</b>
 * <pre>
 * ## 二、平台总体架构            标题，`#` 个数 = 层级
 * &gt; 表 4-1 平台分层技术选型一览   题注，与表同属一块（中间不空行）
 * | 层次 | 主要技术栈 | 主要职责 |
 * |---|---|---|
 * | 用户与访问层 | 浏览器 / 大屏终端 | … |
 * &lt;!-- page: 3 --&gt;              页边界，只用于切片与 payload
 * </pre>
 *
 * <p><b>四条规则：</b>
 * <ol>
 *   <li><b>标题为界先分节</b>：同一节内的正文才允许累积到 500 字，跨节不合并——
 *       否则一块横跨两节，`headingPath` 只能二选一，等于记了个错的归属；</li>
 *   <li><b>表格原子</b>：不按句号切。真的超长时按行分组，每组重述题注与表头，
 *       让每一块单独读都成立；</li>
 *   <li><b>标记剥离</b>：标题行与 {@code <!-- page --&gt;} 不进 {@code content}，
 *       只进 payload。留着会被向量化、被关键词路匹配、被模型照着抄；</li>
 *   <li><b>题注保留</b>：题注是内容（「表 4-1 平台分层技术选型一览」本身有语义），
 *       剥掉 `&gt;` 前缀后留在正文里。</li>
 * </ol>
 *
 * <p><b>纯文本输入走原算法</b>：{@link #chunkPlain} 是从 {@code KnowledgeController}
 * 原样搬来的逐字实现，{@link #chunk} 在检测不到任何 md-lite 标记时直接委托它。
 * 这样「阶段二定下的切分行为」在纯文本场景一次都没变，历史指标可比。
 */
public final class MdLiteChunker {

    /** 中间格式版本号：将来提取器改了输出，能按这个值区分哪批数据是旧格式。 */
    public static final String FORMAT = "md-lite/1";

    /**
     * 格式头标记，提取器输出的第一行。
     *
     * <p><b>为什么需要它，而不靠「有没有 # 行」判断</b>：知识库里有一份
     * {@code riskLevel.py}，它的注释就是 {@code # 计算风险等级}——整行匹配标题正则。
     * 只看 {@code #} 的话，这些注释会被当标题从正文里剥掉，同时把「一级标题」写进
     * 章节路径，等于一边丢正文一边记错归属。所以要有一个显式声明：
     * <b>有头标记才按结构解析，没有就逐字走老算法。</b>
     */
    public static final String FORMAT_MARKER = "<!-- md-lite/1 -->";

    /** 正文块。 */
    public static final String TYPE_TEXT = "text";

    /** 表格块（含题注与表头）。 */
    public static final String TYPE_TABLE = "table";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern PAGE_MARK = Pattern.compile("<!--\\s*page:\\s*(\\d+)\\s*-->");
    private static final Pattern MD_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{3,}.*$");

    private MdLiteChunker() {
    }

    /**
     * 一块的正文 + 它从哪来。
     *
     * @param text        进 {@code content} 的正文（已剥离标题行与页标记）
     * @param headingPath 章节路径，形如 {@code 二、平台总体架构 / （二）总体架构}；无标题时为空串
     * @param blockType   {@link #TYPE_TEXT} / {@link #TYPE_TABLE}
     * @param page        1 基页码；docx 无页概念时为 0（未知，不写 payload）
     */
    public record Chunk(String text, String headingPath, String blockType, int page) {
    }

    /**
     * 文本是否按 md-lite 解析。
     *
     * <p>判据是<b>显式声明</b>而不是猜测：有 {@link #FORMAT_MARKER} 头标记就按结构解析；
     * 没有头标记时，只有出现表格行或页标记才认——这两样在纯文本/代码语料里几乎不会出现，
     * 而 {@code #} 会（Python 注释），所以 {@code #} 单独不作为判据。
     */
    public static boolean hasStructure(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String head = text.stripLeading();
        if (head.startsWith(FORMAT_MARKER)) {
            return true;
        }
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("|") || t.startsWith("> ") || t.startsWith("<!-- page:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 切块。
     *
     * @param text    提取器输出（md-lite 或纯文本）
     * @param max     单块字符上限
     * @param overlap 相邻块共享的边界字数（0 表示不重叠）
     */
    public static List<Chunk> chunk(String text, int max, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (!hasStructure(text)) {
            List<Chunk> plain = new ArrayList<>();
            for (String piece : chunkPlain(text, max, overlap)) {
                plain.add(new Chunk(piece, "", TYPE_TEXT, 0));
            }
            return plain;
        }

        List<Chunk> out = new ArrayList<>();
        String[] path = new String[7];
        StringBuilder section = new StringBuilder();
        int page = 0;
        int sectionPage = 0;

        // 头标记只声明格式，不是内容：切块前先摘掉，否则它会自成一块进正文
        String body = text.stripLeading();
        if (body.startsWith(FORMAT_MARKER)) {
            body = body.substring(FORMAT_MARKER.length());
        }

        for (String rawBlock : body.split("\\n\\s*\\n")) {
            String block = rawBlock.strip();
            if (block.isEmpty()) {
                continue;
            }

            // 整块只有页标记：换页，同时把上一节落下（page 是 payload 的一部分，
            // 一块跨两页只能记一个页码，与其记错不如在边界处断开）
            Matcher whole = PAGE_MARK.matcher(block);
            if (whole.matches()) {
                flush(out, section, path, sectionPage, max, overlap);
                page = Integer.parseInt(whole.group(1));
                sectionPage = page;
                continue;
            }
            // 块内夹着页标记（PDF 提取器可能把标记贴在段首）：剥掉，取最后一个页码
            Matcher inline = PAGE_MARK.matcher(block);
            if (inline.find()) {
                flush(out, section, path, sectionPage, max, overlap);
                page = Integer.parseInt(inline.group(1));
                sectionPage = page;
                block = inline.replaceAll("").strip();
                if (block.isEmpty()) {
                    continue;
                }
            }

            Matcher heading = HEADING.matcher(block);
            if (heading.matches()) {
                flush(out, section, path, sectionPage, max, overlap);
                int level = Math.min(heading.group(1).length(), 6);
                // 同级标题覆盖旧的同级及以下：`# 三、` 之后不该还挂着「二、（五）」
                for (int i = level; i < path.length; i++) {
                    path[i] = null;
                }
                path[level] = heading.group(2).strip();
                continue;
            }

            if (isTableBlock(block)) {
                flush(out, section, path, sectionPage, max, overlap);
                emitTable(out, block, pathOf(path), page, max);
                continue;
            }

            if (section.length() > 0) {
                section.append("\n\n");
            }
            section.append(block);
        }
        flush(out, section, path, sectionPage, max, overlap);
        return out;
    }

    /** 首行是题注或管道表行，就按表格处理。 */
    private static boolean isTableBlock(String block) {
        for (String line : block.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            return t.startsWith("|") || t.startsWith(">");
        }
        return false;
    }

    /** 把当前节的正文交给原算法切，块级元信息在这里快照。 */
    private static void flush(List<Chunk> out, StringBuilder section, String[] path,
                              int page, int max, int overlap) {
        if (section.length() == 0) {
            return;
        }
        String body = section.toString().strip();
        section.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        String headingPath = pathOf(path);
        for (String piece : chunkPlain(body, max, overlap)) {
            out.add(new Chunk(piece, headingPath, TYPE_TEXT, page));
        }
    }

    private static String pathOf(String[] path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < path.length; i++) {
            if (path[i] == null || path[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(path[i]);
        }
        return sb.toString();
    }

    /**
     * 表格成块：整表放得下就一块，放不下按行分组、每组重述题注与表头。
     *
     * <p>行绝不再往下切——把一行切成两半，比多出几块坏得多：`| 用户与访问层 | 浏览器`
     * 这样的半行既不能被检索命中，也没法被人读懂。
     */
    private static void emitTable(List<Chunk> out, String block, String headingPath,
                                  int page, int max) {
        String caption = null;
        List<String> rows = new ArrayList<>();
        for (String line : block.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith(">")) {
                caption = t.substring(1).strip();
            } else if (t.startsWith("|")) {
                rows.add(t);
            }
        }
        if (rows.isEmpty()) {
            // 只有题注没有表：当普通正文处理，别把它吞掉
            if (caption != null && !caption.isEmpty()) {
                out.add(new Chunk(caption, headingPath, TYPE_TEXT, page));
            }
            return;
        }

        String header = rows.get(0);
        String separator = rows.size() > 1 && MD_SEPARATOR.matcher(rows.get(1)).matches()
                ? rows.get(1) : null;
        int bodyStart = separator != null ? 2 : 1;
        List<String> body = new ArrayList<>(rows.subList(Math.min(bodyStart, rows.size()), rows.size()));

        int base = (caption == null ? 0 : caption.length() + 1) + header.length() + 1
                + (separator == null ? 0 : separator.length() + 1);
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLen = base;
        for (String row : body) {
            if (!current.isEmpty() && currentLen + row.length() + 1 > max) {
                groups.add(current);
                current = new ArrayList<>();
                currentLen = base;
            }
            current.add(row);
            currentLen += row.length() + 1;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        if (groups.isEmpty()) {
            groups.add(List.of());
        }

        for (int i = 0; i < groups.size(); i++) {
            StringBuilder sb = new StringBuilder();
            if (caption != null && !caption.isEmpty()) {
                sb.append(caption);
                if (groups.size() > 1) {
                    sb.append("（续 ").append(i + 1).append('/').append(groups.size()).append('）');
                }
                sb.append('\n');
            }
            sb.append(header).append('\n');
            if (separator != null) {
                sb.append(separator).append('\n');
            }
            for (String row : groups.get(i)) {
                sb.append(row).append('\n');
            }
            out.add(new Chunk(sb.toString().strip(), headingPath, TYPE_TABLE, page));
        }
    }

    /**
     * 纯文本切块：按空行分段落，累加到 {@code max} 为一块，相邻块共享 {@code overlap} 字尾。
     *
     * <p>这段是从 {@code KnowledgeController#chunkText} 原样搬来的逐字实现，
     * 不是重写——它已经被 14 个用例钉住，任何"顺手优化"都会让关闭开关的对照失去意义。
     */
    public static List<String> chunkPlain(String text, int max, int overlapRaw) {
        // 重叠不能超过半块：否则下一块的起点会落回上一块中间，产生近乎重复的段。
        int overlap = Math.max(0, Math.min(overlapRaw, max / 2));

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            // 单段本身就超长：先把已有的块落盘，再把这个长段按句子边界切碎。
            if (trimmed.length() > max) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                chunks.addAll(splitLongParagraph(trimmed, max, overlap));
                continue;
            }
            if (current.length() + trimmed.length() > max && current.length() > 0) {
                String body = current.toString().trim();
                chunks.add(body);
                String tail = overlapTail(body, overlap);
                // 尾巴加新段仍超上限（新段本身就很长）：宁可不要尾巴，也不突破单块长度。
                current = new StringBuilder(tail.length() + trimmed.length() > max ? "" : tail);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        if (chunks.isEmpty() && !text.isBlank()) {
            chunks.add(text.trim());
        }
        return chunks;
    }

    /**
     * 把超长段落切成不超过 {@code max} 的若干块，尽量落在句末，避免从词中间截断。
     *
     * <p>找不到句末标点（例如无标点的长数字串、英文表格）时退化为硬切——
     * 有截断总比整段超长好，因为超长段会同时拖累嵌入质量和检索精度。
     */
    private static List<String> splitLongParagraph(String paragraph, int max, int overlap) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(paragraph.length(), start + max);
            if (end < paragraph.length()) {
                int cut = lastSentenceEnd(paragraph, start, end);
                if (cut > start) {
                    end = cut;
                }
            }
            String piece = paragraph.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end >= paragraph.length()) {
                break;
            }
            // 至少前进一个字，防止 overlap 恰好等于块长时死循环
            start = Math.max(start + 1, end - overlap);
        }
        return out;
    }

    /** 在 [from, to) 里找最后一个句末标点之后的切点；找不到返回 -1。 */
    private static int lastSentenceEnd(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 取上一块末尾的重叠文本，长度落在 {@code [overlap / 2, overlap]}；{@code overlap == 0} 时返回空串。
     *
     * <p>优先让尾巴从句子边界开始，避免下一块以半句话开头。但「对齐」不能以牺牲重叠量为代价：
     * 若窗口里第一个句末标点太靠后（对齐后剩余不足 {@code overlap / 2}），说明真正的句界
     * 落在窗口之外，此时退回原始窗口——宁可尾巴从半句开始，也不能把重叠让没了。
     *
     * <p>可见性刻意是 {@code public}：切分不变量的守门测试要直接钉住它的四个边界
     * （窗口内无句号、句号太早、句号太晚、块本身不比窗口长）。只通过 {@code chunkPlain}
     * 间接测，一条失败分不清是哪一分支坏了。
     */
    public static String overlapTail(String body, int overlap) {
        if (overlap <= 0 || body.isEmpty()) {
            return "";
        }
        // 块本身不比窗口长：整块复制给下一块是重复，不是重叠。
        if (body.length() <= overlap) {
            return "";
        }
        int hard = Math.max(0, body.length() - overlap);
        int minKeep = overlap / 2;
        for (int i = hard; i < body.length() - 1; i++) {
            char c = body.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                if (body.length() - (i + 1) >= minKeep) {
                    return body.substring(i + 1).trim();
                }
                break;
            }
        }
        return body.substring(hard).trim();
    }
}
