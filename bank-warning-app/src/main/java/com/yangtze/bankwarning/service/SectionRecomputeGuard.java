package com.yangtze.bankwarning.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「单断面重算」在途登记表：sectionId -> 所属 taskId。
 *
 * <p>为什么不能复用 {@link TaskCancelRegistry}：那张表是**任务级**的，而且兼职当
 * 「本进程是否真在跑这个任务」的心跳探针。单断面重算只跑一条断面、不属于任何一次
 * 任务运行；若借它的 key 注册，重算结束时 {@code unregister(taskId)} 会把正在跑的
 * 整任务登记一并删掉，取消接口随即把该任务判成僵尸（stale）并直接写 cancelled —— 
 * 那是比原问题严重得多的错误。
 *
 * <p>并发策略是**快速失败**而不是排队：同一断面重复提交只会白烧两次模型调用（
 * 每次重算包含 Section View + 风险等级两次模型请求），让后来者立刻收到 409 更诚实。
 */
@Component
public class SectionRecomputeGuard {

    private final Map<String, String> inFlight = new ConcurrentHashMap<>();

    /**
     * 尝试占用一条断面。
     *
     * @return {@code true} = 占用成功，调用方**必须**在 finally 里 {@link #release(String)}；
     *         {@code false} = 已有一次重算在途。
     */
    public boolean tryAcquire(String sectionId, String taskId) {
        return inFlight.putIfAbsent(sectionId, taskId == null ? "" : taskId) == null;
    }

    public void release(String sectionId) {
        inFlight.remove(sectionId);
    }

    /**
     * 该任务下是否还有断面在单算。
     *
     * <p>整任务起跑前必须问一句：{@code runTask} 第一步就是清空该任务全部旧结果，
     * 若此刻有一条单算正把新结果写进去，就会被这次清空顺手删掉，用户看到的是
     * 「刚重算完，结果又没了」。
     */
    public boolean hasInFlightForTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        return inFlight.containsValue(taskId);
    }

    /** 当前在途的断面数，仅用于诊断日志与测试。 */
    public int inFlightCount() {
        return inFlight.size();
    }
}
