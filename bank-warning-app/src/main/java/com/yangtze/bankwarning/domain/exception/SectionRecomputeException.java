package com.yangtze.bankwarning.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 「单断面重算」被拒绝时的冲突（HTTP 409）。
 *
 * <p>这三种拒绝都不是「参数写错了」也不是「东西没了」，而是**此刻不能算**：
 * <ul>
 *   <li>整任务正在跑 —— 它会先清空该任务全部旧结果再逐条重写，此时插一条单算结果会被后面的循环覆盖；</li>
 *   <li>同一个断面已经有一次重算在途 —— 再算一次只是白烧两次模型调用；</li>
 *   <li>该任务下有断面正在单算 —— 整任务起跑前的清空会把它的结果删掉。</li>
 * </ul>
 * 都不能用 IllegalArgumentException：全局处理器把它固定映射成 404，前端会误以为断面不存在。
 */
public class SectionRecomputeException extends RuntimeException {

    private final String code;
    private final String sectionId;

    private SectionRecomputeException(String code, String message, String sectionId) {
        super(message);
        this.code = code;
        this.sectionId = sectionId;
    }

    /** 同一断面已有一次重算在途。 */
    public static SectionRecomputeException sectionBusy(String sectionId) {
        return new SectionRecomputeException(
                "SECTION_RECOMPUTING",
                "该断面正在重算中，请等待当前这次完成后再试",
                sectionId);
    }

    /** 该断面所属任务正在整体运行。 */
    public static SectionRecomputeException taskRunning(String taskId, String sectionId) {
        return new SectionRecomputeException(
                "TASK_RUNNING",
                "该断面所属任务（" + taskId + "）正在运行，请等任务结束后再单独重算该断面",
                sectionId);
    }

    /** 该任务下有断面正在单算，整任务此刻不能起跑。 */
    public static SectionRecomputeException taskHasRunningSection(String taskId) {
        return new SectionRecomputeException(
                "SECTION_RECOMPUTING",
                "该任务下有断面正在单独重算，请等它完成后再运行任务（避免整体清空把它的结果删掉）",
                null);
    }

    public String getCode() {
        return code;
    }

    public String getSectionId() {
        return sectionId;
    }

    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}
