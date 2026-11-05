package com.yangtze.bankwarning.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 人工修改结果时的可预期失败。
 *
 * 不复用 IllegalArgumentException：现有全局处理器把它固定映射成 404，
 * 而这里的两种失败都是「请求本身有问题」——参数不合法应该是 400、
 * 客户端看到的结果已被新一轮运行覆盖应该是 409。用错状态码会让前端
 * 无法区分「断面没了」和「该刷新了」。
 */
public class ResultOverrideException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ResultOverrideException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 参数不合法（范围、枚举、风险值与等级不一致……），前端应当留在编辑态。 */
    public static ResultOverrideException invalid(String message) {
        return new ResultOverrideException(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE", message);
    }

    /** 客户端手上的结果已不是最新一行（任务重跑过），必须刷新后重来。 */
    public static ResultOverrideException stale(String message) {
        return new ResultOverrideException(HttpStatus.CONFLICT, "STALE_RESULT", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
