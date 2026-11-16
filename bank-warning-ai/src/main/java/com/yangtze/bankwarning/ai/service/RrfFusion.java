package com.yangtze.bankwarning.ai.service;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（RRF）—— 阶段 5 把两路召回合成一份名单。
 *
 * <p><b>为什么不能直接加分数</b>：向量路给的是余弦相似度（0~1，越大越像），
 * 关键词路给的是命中锚点数（整数，语义是「字面命中几处」）。两个分量纲完全不同，
 * 直接相加或加权都是拍脑袋。RRF 只用「各自排第几名」：{@code 1/(k + rank)}，
 * 天然无量纲，也不需要归一化。
 *
 * <p><b>只有一路有结果时不做融合</b>：直接原样返回那一份，并<b>保留其原始分数</b>。
 * 这条很关键——纯中文语义问题提不出任何精确锚点，关键词路返回空，此时若仍走 RRF
 * 会把余弦相似度改写成 RRF 分，导致「开关打开但无锚点」与关闭态结果不一致，
 * 既破坏 4A 基线可比性，也让留痕里的 {@code score} 语义变得不可解释。
 *
 * <p>纯静态、无状态、不连库，单测可直接构造 {@link Document} 验证。
 */
public final class RrfFusion {

    /** 去重键的分隔符：用不可见控制字符，避免与 docId / chunkId 自身字符冲突。 */
    private static final char KEY_SEP = '\u0001';

    private RrfFusion() {
    }

    /**
     * @param dense  向量路召回（已按余弦距离升序，即越靠前越相似）
     * @param sparse 关键词路召回（已按命中锚点数降序）
     * @param rrfK   平滑常数，常用 60
     * @param limit  最终返回条数
     */
    public static List<Document> fuse(List<Document> dense, List<Document> sparse, int rrfK, int limit) {
        List<Document> d = dense == null ? List.of() : dense;
        List<Document> s = sparse == null ? List.of() : sparse;
        int k = rrfK > 0 ? rrfK : 60;
        int cap = limit > 0 ? limit : 1;

        // 单路：原样返回，不改写 score（见类注释）
        if (s.isEmpty()) {
            return head(d, cap);
        }
        if (d.isEmpty()) {
            return head(s, cap);
        }

        Map<String, Double> fusedScore = new LinkedHashMap<>();
        Map<String, Document> byKey = new LinkedHashMap<>();
        accumulate(d, k, fusedScore, byKey);
        accumulate(s, k, fusedScore, byKey);

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(fusedScore.entrySet());
        // 稳定排序：同分时保持「先被 dense 收进来的」在前，避免同分抖动导致结果不可复现。
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Document> out = new ArrayList<>(Math.min(cap, ranked.size()));
        for (Map.Entry<String, Double> entry : ranked) {
            if (out.size() >= cap) {
                break;
            }
            Document doc = byKey.get(entry.getKey());
            // 不原地 setScore：那会把调用方传进来的 Document 改掉（同一对象被两次 fuse 复用时
            // 分数会互相覆盖）。metadata 不可变，拷贝一份承载 RRF 分即可。
            // 融合后 score 的语义变成「无量纲 RRF 分」，与关闭态的余弦相似度不可直接比较，
            // 口径见 docs/rag-hybrid-phase5-design.md，留痕表不加列。
            Document scored = new Document(doc.getMetadata());
            scored.setScore(entry.getValue());
            out.add(scored);
        }
        return out;
    }

    /** 跨路去重键：同一段（docId + chunkId）在两路必须落到同一个 key。 */
    static String key(Document doc) {
        DocumentMetadata meta = doc.getMetadata();
        if (meta != null) {
            String chunkId = meta.getChunkId();
            if (chunkId != null && !chunkId.isBlank()) {
                String docId = meta.getDocId();
                return (docId == null ? "" : docId) + KEY_SEP + chunkId;
            }
        }
        String id = doc.getId();
        return id == null ? "identity:" + System.identityHashCode(doc) : id;
    }

    private static void accumulate(List<Document> docs, int k,
                                   Map<String, Double> score, Map<String, Document> byKey) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String key = key(doc);
            score.merge(key, 1.0 / (k + i + 1), Double::sum);
            byKey.putIfAbsent(key, doc);
        }
    }

    private static List<Document> head(List<Document> docs, int cap) {
        return docs.size() <= cap ? List.copyOf(docs) : List.copyOf(docs.subList(0, cap));
    }
}
