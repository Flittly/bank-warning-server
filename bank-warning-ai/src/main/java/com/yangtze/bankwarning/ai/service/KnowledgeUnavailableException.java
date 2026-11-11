package com.yangtze.bankwarning.ai.service;

/**
 * 「这次没有知识可用」的确定性原因 —— 阶段 1。
 *
 * <p>改造前 {@code KnowledgeService.retrieve()} 用一个宽泛的 catch 把任何异常
 * 都吞成空列表，于是「密钥没配」「没登录」「向量库连不上」和「确实没检索到」
 * 在下游完全无法区分，AI 照样流利作答。现在前三类一律抛本异常，由调用点
 * 转成用户看得见的话术；只有「确实没命中」才返回空列表。
 */
public class KnowledgeUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** 配置里显式关闭了检索。 */
        DISABLED,
        /** DashScope API Key 未配置，嵌入模型是占位实现。 */
        UNCONFIGURED,
        /** 这次调用没有携带有效登录身份，按 fail-closed 拒绝。 */
        UNAUTHENTICATED,
        /** 查询向量生成失败（网络、限流、超时等）。 */
        EMBED_FAILED,
        /** 向量检索或权限查询失败。 */
        SEARCH_FAILED
    }

    private final Reason reason;

    public KnowledgeUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public KnowledgeUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
