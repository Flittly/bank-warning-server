package com.yangtze.bankwarning.ai.store;

/**
 * 阶段 5：检索「成功但一条都没返回」的原因。
 *
 * <p><b>为什么需要它</b>：空列表是一个歧义值。它可能是「库是空的」「问法和资料用词差太远」
 * 「这批资料不归你看」「精排判定都答不了」四种完全不同的事，而这四种事该做的处理也完全不同
 * ——补语料、改写问法、申请权限、换个问法。改造前四种都表现为同一个空数组，
 * 模型只能笼统地说「知识库没有」，于是把「阈值切掉了」说成「没有这条规范」。
 *
 * <p><b>口径</b>：本枚举只描述<b>检索层</b>的判断，不涉及生成层是否愿意回答。
 * 落库时用 {@link #code()}（枚举名），对用户/模型说话时用 {@link #message()}。
 */
public enum RagEmptyReason {

    /** 粗召回一条都没有：库为空、查询与库无交集，或权限之外根本没有可比内容。 */
    NO_CANDIDATE("知识库里没有任何段落与这个问题匹配"),

    /** 粗召回取到过候选，但它们全部低于召回底线（向量相似度门槛）。 */
    BELOW_RECALL_FLOOR("有几段内容比较接近，但相似度都低于当前召回门槛"),

    /** 候选存在，但全部落在当前账号不可见的 doc 里。 */
    PERMISSION_TRIMMED("命中的资料不在当前账号的可见范围内"),

    /** 有可见候选，但精排判断它们都不足以回答（精排门槛把它们全滤掉了）。 */
    BELOW_FINAL_FLOOR("找到了候选段落，但精排判断它们都不足以回答这个问题");

    private final String message;

    RagEmptyReason(String message) {
        this.message = message;
    }

    /** 落库值，同时也是对外契约的一部分：改名前先确认没有报表在读它。 */
    public String code() {
        return name();
    }

    /** 给模型/用户看的一句话，不带技术术语。 */
    public String message() {
        return message;
    }

    /**
     * 按落库值反查文案；未知值（历史行、手工改过的行）返回空串，
     * 由调用方决定是省略原因还是原样展示，不抛异常。
     */
    public static String messageOf(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        for (RagEmptyReason r : values()) {
            if (r.name().equals(code)) {
                return r.message;
            }
        }
        return "";
    }
}
