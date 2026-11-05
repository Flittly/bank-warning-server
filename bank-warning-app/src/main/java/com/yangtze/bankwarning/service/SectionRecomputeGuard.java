package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.exception.SectionRecomputeException;
import com.yangtze.bankwarning.service.cluster.ClusterLockManager;
import org.springframework.stereotype.Component;

/**
 * 「单断面重算」的准入闸门：按**共享/独占**语义和整任务运行互斥。
 *
 * <p><b>历史</b>：这个类最初是一张进程内的在途登记表（{@code sectionId -> taskId}）。
 * 现在它只剩两层薄封装，把两件事交给 {@link ClusterLockManager}：
 * <ol>
 *   <li><b>对任务持共享锁</b> —— 等价于原来的 {@code hasInFlightForTask}：
 *       「该任务下有断面在单算」。共享锁的好处是**多条不同断面可以同时单算**，
 *       而整任务运行（独占锁）一进来就把它们全挡住。</li>
 *   <li><b>对该断面持独占锁</b> —— 等价于原来的在途表：同一断面不许重复提交。
 *       换到集群模式下它依然成立，而原来那张表只能管住本进程。</li>
 * </ol>
 *
 * <p><b>为什么不能复用 {@link TaskCancelRegistry}</b>：那张表是**任务级**的，而且兼职当
 * 「本进程是否真在跑这个任务」的心跳探针。单断面重算只跑一条断面、不属于任何一次任务运行；
 * 若借它的 key 注册，重算结束时 {@code unregister(taskId)} 会把正在跑的整任务登记一并删掉，
 * 取消接口随即把该任务判成僵尸（stale）并直接写 cancelled —— 那是比原问题严重得多的错误。
 *
 * <p><b>并发策略是快速失败而不是排队</b>：同一断面重复提交只会白烧两次模型调用
 * （每次重算包含 Section View + 风险等级两次模型请求），让后来者立刻收到 409 更诚实。
 *
 * <p><b>锁的获取顺序固定为「先任务、后断面」</b>，释放逆序。所有调用点都必须守这个顺序，
 * 否则两个方向相反的持锁线程会互相等死。目前只有 {@code TaskExecutionService} 一个调用点。
 */
@Component
public class SectionRecomputeGuard {

    /** 任务级锁：整任务运行要独占它，单断面重算只要共享。 */
    private static final String TASK_LOCK_PREFIX = "bw:lock:task-run:";
    /** 断面级锁：同一断面同一时刻只允许一次重算。 */
    private static final String SECTION_LOCK_PREFIX = "bw:lock:section:";

    private final ClusterLockManager lockManager;

    public SectionRecomputeGuard(ClusterLockManager lockManager) {
        this.lockManager = lockManager;
    }

    public static String taskLockKey(String taskId) {
        return TASK_LOCK_PREFIX + taskId;
    }

    public static String sectionLockKey(String sectionId) {
        return SECTION_LOCK_PREFIX + sectionId;
    }

    /**
     * 占用「重算这一条断面」所需的全部锁，失败直接抛 409。
     *
     * <p>返回的句柄**必须**在 finally 里 {@code close()}，否则任务锁的共享计数一直挂着，
     * 该任务就再也跑不了整任务（独占锁永远抢不到），直到进程重启或 Redis 键过期。
     *
     * @return 组合句柄（内部含任务共享锁 + 断面独占锁），关闭时按逆序释放
     * @throws SectionRecomputeException 任务正在整体运行（{@code TASK_RUNNING}），
     *                                   或该断面已有一条重算在途（{@code SECTION_RECOMPUTING}）
     */
    public ClusterLockManager.LockHandle acquire(String sectionId, String taskId) {
        ClusterLockManager.LockHandle taskScope = lockManager.tryLockShared(taskLockKey(taskId));
        if (taskScope == null) {
            // 抢不到任务共享锁 ⇒ 有人正以独占模式跑整个任务。
            throw SectionRecomputeException.taskRunning(taskId, sectionId);
        }
        ClusterLockManager.LockHandle sectionScope;
        try {
            sectionScope = lockManager.tryLockExclusive(sectionLockKey(sectionId));
        } catch (RuntimeException exception) {
            // 第二把锁抛异常（Redis 抖动）时第一把已经拿到了，必须先还回去，
            // 否则这个任务从此再也跑不了整任务。这类「部分成功」是最容易被忘的分支。
            taskScope.close();
            throw exception;
        }
        if (sectionScope == null) {
            taskScope.close();
            throw SectionRecomputeException.sectionBusy(sectionId);
        }
        return new Scope(taskScope, sectionScope);
    }

    /**
     * 尝试以独占模式占用「整任务运行」的锁。
     *
     * <p>拿不到只有两种可能，都意味着此刻不该起跑：
     * <ul>
     *   <li>另一个实例/线程正在跑同一个任务（它自己会写终态）；</li>
     *   <li>该任务下有断面正在单独重算（整任务第一步要清空全部旧结果，会把它的成果顺手删掉）。</li>
     * </ul>
     * 这里不抛异常而是返回 {@code null}，让调用方自己决定报什么错 ——
     * Kafka 分发路径与 HTTP 路径的文案不一样。
     */
    public ClusterLockManager.LockHandle tryLockTaskExclusive(String taskId) {
        return lockManager.tryLockExclusive(taskLockKey(taskId));
    }

    /**
     * 该任务此刻是否被锁住（任何实例、任何模式）。
     *
     * <p>注意它把「整任务在跑」和「有断面在单算」合并成了一个答案 —— 因为两者共用同一把
     * 任务级锁。要做区分诊断请直接看日志里 {@code [cluster-lock]} 的 {@code exclusive} 字段。
     */
    public boolean isTaskLocked(String taskId) {
        return lockManager.isHeld(taskLockKey(taskId));
    }

    /** 组合句柄：按「断面 → 任务」的逆序释放，close 幂等。 */
    private static final class Scope implements ClusterLockManager.LockHandle {

        private final ClusterLockManager.LockHandle taskScope;
        private final ClusterLockManager.LockHandle sectionScope;
        private boolean released;

        private Scope(ClusterLockManager.LockHandle taskScope,
                      ClusterLockManager.LockHandle sectionScope) {
            this.taskScope = taskScope;
            this.sectionScope = sectionScope;
        }

        @Override
        public String key() {
            return sectionScope.key();
        }

        @Override
        public boolean exclusive() {
            return true;
        }

        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            released = true;
            try {
                sectionScope.close();
            } finally {
                // 任务锁必须在断面锁之后放：反过来的话，中间那个瞬间会出现
                // 「断面锁还占着，但整任务已经可以起跑」，而整任务会清空该断面结果。
                taskScope.close();
            }
        }
    }
}
