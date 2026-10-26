package com.yangtze.bankwarning.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 岸段几何编辑的可预期失败。
 *
 * <p>不复用 {@link IllegalArgumentException}：全局处理器把它固定映射成 404，
 * 而「折点少于 2 个」「坐标非有限数」都是请求本身不合法（400），
 * 前端要据此留在编辑态、把最后一步撤销掉，而不是当成岸段不存在。
 */
public class BankGeometryException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private BankGeometryException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 几何不合法（类型不对、折点不足、坐标非法……），前端应回滚这一次改动。 */
    public static BankGeometryException invalid(String message) {
        return new BankGeometryException(HttpStatus.BAD_REQUEST, "INVALID_BANK_GEOMETRY", message);
    }

    /** 岸段在库里不存在（或已软删）。 */
    public static BankGeometryException notFound(String bankId) {
        return new BankGeometryException(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "岸段不存在：" + bankId);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
