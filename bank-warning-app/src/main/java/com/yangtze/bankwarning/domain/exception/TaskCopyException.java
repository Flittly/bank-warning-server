package com.yangtze.bankwarning.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 「复制任务」的可预期失败。
 *
 * <p>为什么不复用 {@link IllegalArgumentException}：全局处理器把它固定映射成 404，
 * 而这三种失败都不是「任务不存在」——
 * <ul>
 *   <li>目标 id 撞车 / 源任务正在运行（结果不完整）应该是 <b>409</b>，前端据此提示「换个名字」或「等跑完再复制」；</li>
 *   <li>{@code target_task_id} 格式不对应该是 <b>400</b>，让调用方改参数；</li>
 *   <li>跨用户复制未开放应该是 <b>403</b>，而不是「找不到任务」的假象。</li>
 * </ul>
 * 用错状态码会让前端无法区分「任务没了」和「这次请求本身不合法」。
 */
public class TaskCopyException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private TaskCopyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 入参不合法（id 格式 / 长度 / 自我复制），改参数可重试。 */
    public static TaskCopyException invalid(String message) {
        return new TaskCopyException(HttpStatus.BAD_REQUEST, "INVALID_COPY_REQUEST", message);
    }

    /** 请求合法但此刻不能做（id 已被占用、源任务在跑导致结果不完整）。 */
    public static TaskCopyException conflict(String code, String message) {
        return new TaskCopyException(HttpStatus.CONFLICT, code, message);
    }

    /** 没有权限做这件事（当前只有跨用户复制会走到）。 */
    public static TaskCopyException forbidden(String message) {
        return new TaskCopyException(HttpStatus.FORBIDDEN, "FORBIDDEN_TARGET_USER", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
