package com.yangtze.bankwarning.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 「断面人工无效」的可预期失败：入参不合法（{@code invalid} 缺失 / ids 为空 / 原因超长）。
 *
 * <p>为什么不复用 {@link IllegalArgumentException}：全局处理器把它**固定映射成 404**，
 * 而这三种都是「这一请求本身拼错了」，与「断面不存在」毫无关系。前端拿到 404 会当成
 * "断面已被删"从而关掉弹窗，用户会以为自己点错了对象 —— 真正的原因其实是请求缺字段。
 * 所以必须 400，让前端把 message 原样显示在弹窗里、保持当前断面不动。
 *
 * <p>「一条都没匹配上」（越权 / 已被删）**不走异常**：批量操作里可能只有一部分失效，
 * 用 200 + {@code updated} 条数更能表达真实结果，前端据 {@code updated < requested}
 * 提示「N 条未生效」。
 */
public class SectionManualInvalidException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private SectionManualInvalidException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 入参不合法，改参数即可重试。 */
    public static SectionManualInvalidException invalid(String message) {
        return new SectionManualInvalidException(
                HttpStatus.BAD_REQUEST, "INVALID_MANUAL_INVALID_REQUEST", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
