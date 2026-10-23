package com.yangtze.bankwarning.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单进程内的中断登记表（默认实现）。
 *
 * <p>语义只在一个 JVM 里成立：{@code requestCancel} 返回 {@code false} 时，调用方会认定
 * 「跑它的进程已经不在了」并直接改库。单实例部署下这个推论是对的；
 * 多实例部署下就成了误判 —— 所以那种场景必须换成 {@link RedisTaskCancelRegistry}。
 */
@Component
@ConditionalOnProperty(prefix = "bw.cluster", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTaskCancelRegistry implements TaskCancelRegistry {

    /** taskId -> 是否已被请求取消。用 Boolean 值而不是 Set，是为了保留「登记过但还没取消」这一状态。 */
    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();

    @Override
    public void register(String taskId) {
        flags.put(taskId, Boolean.FALSE);
    }

    @Override
    public void unregister(String taskId) {
        flags.remove(taskId);
    }

    @Override
    public boolean isRunning(String taskId) {
        return flags.containsKey(taskId);
    }

    @Override
    public boolean requestCancel(String taskId) {
        // ConcurrentHashMap#replace 只在 key 存在时写入，返回旧值 ⇒ 非 null 即说明表里有它
        return flags.replace(taskId, Boolean.TRUE) != null;
    }

    @Override
    public boolean isCancelRequested(String taskId) {
        return Boolean.TRUE.equals(flags.get(taskId));
    }

    /**
     * 内存实现没有「所有权」概念（本进程登记的就是本进程在跑），所以只可能给出两种结论。
     * 也正因为如此，它天然不会返回 {@link CancelSignal#LOST_OWNERSHIP}。
     */
    @Override
    public CancelSignal heartbeatAndCheck(String taskId) {
        return isCancelRequested(taskId) ? CancelSignal.CANCEL_REQUESTED : CancelSignal.CONTINUE;
    }
}
