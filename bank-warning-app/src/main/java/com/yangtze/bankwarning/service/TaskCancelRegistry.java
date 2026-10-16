package com.yangtze.bankwarning.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本进程内「正在跑」的任务的中断登记表。
 *
 * <p>存在的理由：任务是在一个 HTTP 请求线程里同步逐断面跑的（见 {@link TaskExecutionService#runTask}），
 * 想中途停下来只能靠一个内存标志让那个循环自己退出 —— 数据库里的 {@code tasks.status}
 * 只是一个字符串，改它并不会让任何线程停下来（这正是「假停止」的成因）。
 *
 * <p>因此这张表还兼职当「心跳探针」：
 * <ul>
 *   <li>表里有这个 taskId ⇒ 本进程**确实**有线程在跑它，可以协作式取消；</li>
 *   <li>表里没有 ⇒ 要么它本来就没在跑，要么跑它的进程已经不存在了（后端重启 / 请求中断留下的僵尸），
 *       此时调用方应当**直接改库收尾**，否则那条 {@code running} 会永远挂着。</li>
 * </ul>
 *
 * <p>注意：这是**单实例**语义。本应用按单 JVM 部署（本地 dev / 单机生产）成立；
 * 将来若做多实例，需要换成 Redis 标志 + 实例心跳。
 */
@Component
public class TaskCancelRegistry {

    /** taskId -> 是否已被请求取消。用 Boolean 值而不是 Set，是为了保留「登记过但还没取消」这一状态。 */
    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();

    /** 任务开始跑时登记。重复登记（同一任务被再跑一次）会重置上一个残留标志。 */
    public void register(String taskId) {
        flags.put(taskId, Boolean.FALSE);
    }

    /** 任务收尾时注销（成功 / 失败 / 被取消都要调），否则登记表只增不减。 */
    public void unregister(String taskId) {
        flags.remove(taskId);
    }

    public boolean isRunning(String taskId) {
        return flags.containsKey(taskId);
    }

    /**
     * 请求取消。
     *
     * @return {@code true} = 本进程确实在跑这个任务，标志已置位，循环会在当前断面算完后退出；
     *         {@code false} = 本进程没在跑它（空闲，或着是进程重启留下的僵尸）。
     */
    public boolean requestCancel(String taskId) {
        // ConcurrentHashMap#replace 只在 key 存在时写入，返回旧值 ⇒ 非 null 即说明表里有它
        return flags.replace(taskId, Boolean.TRUE) != null;
    }

    public boolean isCancelRequested(String taskId) {
        return Boolean.TRUE.equals(flags.get(taskId));
    }
}
