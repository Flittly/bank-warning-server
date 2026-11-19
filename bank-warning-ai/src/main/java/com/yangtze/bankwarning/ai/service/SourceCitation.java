package com.yangtze.bankwarning.ai.service;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段 4：把检索结果渲染成<b>可追溯</b>的资料块。
 *
 * <p><b>要解决什么：</b>检索结果原先只把正文丢给模型（{@code join("\n---\n")}），
 * 不带文件名、不带段号。后果有两个：模型无法说「依据出自哪份文件第几段」，
 * 用户也无法回查——规范条文类问答里，无法回查的答案是没法用的。
 *
 * <p><b>输出形状：</b>
 * <pre>
 * [1] 来源：BMS.docx · 第 3/4 段
 * 正文……
 *
 * [2] 来源：3-项目技术报告.docx · 第 12/239 段
 * 正文……
 * </pre>
 *
 * <p><b>段号是真源里的 0 基序号</b>：入库时 payload 写的是 {@code chunkIndex=0,1,2…}，
 * {@code chunk_id} 的后缀 {@code _chunk_<i>} 同源。对外显示统一 +1，否则「第 0 段」
 * 会被当成解析失败。payload 缺失（早期数据、评测探针）时从 {@code chunk_id} 兜底；
 * 两者都没有就只显示文件名，<b>不猜</b>。
 *
 * <p><b>总段数可能缺失</b>：单 chunk 文档、或人工插入的行没有 {@code totalChunks}，
 * 此时只显示「第 k 段」。宁可少一个分母，也不写一个错的。
 */
public final class SourceCitation {

    /** 入库时写入的 0 基段号，见 {@code KnowledgeController#chunkDocuments}。 */
    static final String PAYLOAD_CHUNK_INDEX = "chunkIndex";

    /** 入库时写入的段总数。 */
    static final String PAYLOAD_TOTAL_CHUNKS = "totalChunks";

    /** 兜底：{@code template.json_chunk_0} → 0。 */
    private static final Pattern CHUNK_ID_SUFFIX = Pattern.compile("_chunk_(\\d+)$");

    /** docId 为空时的占位，不能显示 "null"。 */
    static final String UNKNOWN_DOC = "未命名资料";

    /**
     * 给模型的引用契约。放在资料块之后，与 {@link #render} 配套使用。
     *
     * <p>为什么写进工具返回值而不是 system prompt：工具在 ReAct 循环里被调用，
     * 返回值每一轮都会重新进入上下文，比只在首轮出现一次的系统提示更难被遗忘。
     */
    public static final String RULE = """
            引用规则（必须遵守）：
            1. 上面每条资料都有编号 [n]，回答中用到某条依据时必须标注对应编号，例如「按三级阈值划分…[2]」；
            2. 只能标注上面出现过的编号，不得编造编号、文件名或段号；
            3. 资料没有覆盖的部分，直接说明「知识库中没有相关依据」，不得用常识或推测冒充规范条文；
            4. 同一处依据引用多份资料时写成 [1][3]。
            """;

    private SourceCitation() {
    }

    /**
     * 渲染全部命中，编号从 1 开始，块间空一行。
     *
     * @param docs 已按相关度排序的命中；空列表返回空串，由调用方决定如何提示「没查到」
     */
    public static String render(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(docs.size() * 256);
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            sb.append('[').append(i + 1).append("] 来源：").append(label(doc)).append('\n')
                    .append(text(doc)).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 单条来源标签：{@code BMS.docx · 第 3/4 段}。
     *
     * <p>三种降级：段号未知 → 只有文件名；总段数未知 → 「第 k 段」；文件名为空 → 占位文案。
     */
    public static String label(Document doc) {
        String docId = docId(doc);
        int no = chunkNo(doc);
        if (no < 0) {
            return docId;
        }
        int total = totalChunks(doc);
        if (total > 0 && total >= no) {
            return docId + " · 第 " + no + "/" + total + " 段";
        }
        return docId + " · 第 " + no + " 段";
    }

    /** 展示用的 1 基段号；未知返回 -1。 */
    public static int chunkNo(Document doc) {
        Integer zeroBased = intPayload(doc, PAYLOAD_CHUNK_INDEX);
        if (zeroBased != null && zeroBased >= 0) {
            return zeroBased + 1;
        }
        String chunkId = chunkId(doc);
        if (chunkId != null) {
            Matcher m = CHUNK_ID_SUFFIX.matcher(chunkId);
            if (m.find()) {
                return Integer.parseInt(m.group(1)) + 1;
            }
        }
        return -1;
    }

    /** 段总数；未知返回 -1。 */
    public static int totalChunks(Document doc) {
        Integer total = intPayload(doc, PAYLOAD_TOTAL_CHUNKS);
        return total != null && total > 0 ? total : -1;
    }

    /** 文件名；空值统一成占位文案，避免提示词里出现 "null"。 */
    public static String docId(Document doc) {
        DocumentMetadata meta = doc == null ? null : doc.getMetadata();
        if (meta == null) {
            return UNKNOWN_DOC;
        }
        String docId = meta.getDocId();
        return docId == null || docId.isBlank() ? UNKNOWN_DOC : docId;
    }

    private static String chunkId(Document doc) {
        DocumentMetadata meta = doc == null ? null : doc.getMetadata();
        if (meta == null) {
            return null;
        }
        String chunkId = meta.getChunkId();
        return chunkId == null || chunkId.isBlank() ? null : chunkId;
    }

    /**
     * 正文。优先 {@code getContentText()}（TextBlock 的原样文本），
     * 取不到再退回 {@code ContentBlock.toString()}——不同 SDK 版本对非文本块的
     * 行为不一致，这里不做类型断言。
     */
    private static String text(Document doc) {
        DocumentMetadata meta = doc == null ? null : doc.getMetadata();
        if (meta == null || meta.getContent() == null) {
            return "";
        }
        String text = meta.getContentText();
        return text != null ? text : String.valueOf(meta.getContent());
    }

    /** payload 里取整数。值可能是 String（入库时统一写字符串）或 Number（外部写入）。 */
    private static Integer intPayload(Document doc, String key) {
        DocumentMetadata meta = doc == null ? null : doc.getMetadata();
        if (meta == null) {
            return null;
        }
        Object raw = meta.getPayloadValue(key);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            // payload 是弱类型的外部输入，脏值按「未知」处理，不影响检索主流程。
            return null;
        }
    }
}
