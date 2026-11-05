package com.yangtze.bankwarning.service;

/**
 * 「谁在跑这个任务」+「用户是否要求它停」的登记表。
 *
 * <p>存在的理由：任务是在一个 HTTP 请求线程里同步逐断面跑的（见 {@link TaskExecutionService#runTask}），
 * 想中途停下来只能靠一个标志让那个循环自己退出 —— 数据库里的 {@code tasks.status}
 * 只是一个字符串，改它并不会让任何线程停下来（这正是「假停止」的成因）。
 *
 * <p>因此这张表还兼职当「存活探针」，{@link #requestCancel} 的返回值就是它的第二个职责：
 * <ul>
 *   <li>{@code true} ⇒ 确实有实例在跑这个任务，可以协作式取消；</li>
 *   <li>{@code false} ⇒ 要么它本来就没在跑，要么跑它的进程已经不存在了（后端重启 / 请求中断留下的僵尸），
 *       此时调用方应当**直接改库收尾**，否则那条 {@code running} 会永远挂着。</li>
 * </ul>
 *
 * <p><b>两个实现的取舍</b>：
 * <ul>
 *   <li>{@link InMemoryTaskCancelRegistry} —— 单 JVM 内存表，默认启用，零外部依赖。
 *       语义只在一个进程内成立。</li>
 *   <li>{@link RedisTaskCancelRegistry} —— 所有实例共享，{@code bw.cluster.enabled=true} 时启用。
 *       多实例部署必须用它：否则 A 在跑、取消请求被 nginx 轮到 B，B 会误判成僵尸
 *       并把库写成 cancelled，而 A 毫不知情继续算 —— 状态与事实反向不一致。</li>
 * </ul>
 */
public interface TaskCancelRegistry {

    /**
     * 心跳检查的三种结论。合并「续期租约 + 查取消标志 + 校验所有权」为一次调用，
     * 是因为这三件事在断面循环里必须同时成立才有意义（分开查会存在缝隙）。
     */
    enum CancelSignal {
        /** 一切正常，继续算下一条断面。 */
        CONTINUE,
        /** 用户要求终止：算完当前断面就停，由本循环写终态。 */
        CANCEL_REQUESTED,
        /**
         * 本实例已经不拥有这个任务了：租约键被别人覆盖（同一任务被另一次运行接管）或已过期。
         * 此时应当停止计算，但**不要写终态** —— 终态归接管者写。
         */
        LOST_OWNERSHIP
    }

    /** 任务开始跑时登记。重复登记（同一任务被再跑一次）会重置上一个残留的取消标志。 */
    void register(String taskId);

    /** 任务收尾时注销（成功 / 失败 / 被取消都要调），否则登记只增不减。 */
    void unregister(String taskId);

    boolean isRunning(String taskId);

    /**
     * 请求取消。
     *
     * @return {@code true} = 确实有实例在跑这个任务，标志已置位，循环会在当前断面算完后退出；
     *         {@code false} = 没有任何实例在跑它（空闲，或着是进程重启留下的僵尸）。
     */
    boolean requestCancel(String taskId);

    boolean isCancelRequested(String taskId);

    /**
     * 断面循环的检查点：一次调用同时完成「续期租约」「查取消标志」「确认自己还是所有者」。
     *
     * <p>为什么不拆成三个方法：三者之间存在竞态 —— 先查所有权、再查取消、再续期，
     * 中间任何一步之后状态都可能变。合并成一次原子操作（Redis 实现走 Lua）才没有缝隙。
     */
    CancelSignal heartbeatAndCheck(String taskId);
}
