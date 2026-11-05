package com.yangtze.bankwarning.service.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 进程内的读写锁（默认实现）。
 *
 * <p><b>为什么用「全局 synchronized + HashMap 计数」而不是 {@code ReentrantReadWriteLock}</b>：
 * JDK 的读写锁是**按对象**的，而这里需要**按 key 动态**加锁。用
 * {@code Map<String, ReentrantReadWriteLock>} 能实现，但「用完即删」在可重入语义下很难做对 ——
 * 删除前要确认既无人持有也无人排队，而检查与删除之间仍有竞态，删错就会让后续请求
 * 拿到一把全新的锁，互斥当场失效（这类 bug 只在并发下偶发，极难复现）。
 *
 * <p>换成「一把 monitor 保护一个 {@code HashMap<String,int[]>}」就没有这个问题：
 * 判据与修改在同一个临界区里，{@code readers}/{@code writer} 都归零时立即删除条目，
 * 既不泄漏也不会误删。代价是每次加锁都要抢一次全局 monitor —— 但锁操作不在热路径上
 * （每个断面一次，而一个断面要算好几秒），微秒级的争用完全无感。
 *
 * <p>单实例部署下这就是全部正确性来源；多实例必须换成 Redis 实现，
 * 否则两个实例各自持有一把「互斥锁」，什么也没互斥到。
 */
@Component
@ConditionalOnProperty(prefix = "bw.cluster", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalClusterLockManager implements ClusterLockManager {

    /** key -> [readerCount, writerFlag]。writerFlag 取 0/1。 */
    private final Map<String, int[]> states = new HashMap<>();

    private static final int READERS = 0;
    private static final int WRITER = 1;

    @Override
    public synchronized LockHandle tryLockShared(String key) {
        int[] state = states.computeIfAbsent(key, ignored -> new int[2]);
        if (state[WRITER] == 1) {
            // 独占正被占：**不能**在失败时留下空条目，否则 states 会积累没人认领的 key。
            removeIfIdle(key, state);
            return null;
        }
        state[READERS]++;
        return new Handle(key, false);
    }

    @Override
    public synchronized LockHandle tryLockExclusive(String key) {
        int[] state = states.computeIfAbsent(key, ignored -> new int[2]);
        if (state[WRITER] == 1 || state[READERS] > 0) {
            removeIfIdle(key, state);
            return null;
        }
        state[WRITER] = 1;
        return new Handle(key, true);
    }

    @Override
    public synchronized boolean isHeld(String key) {
        int[] state = states.get(key);
        return state != null && (state[WRITER] == 1 || state[READERS] > 0);
    }

    /** 诊断/测试用：当前有多少个 key 被占用。 */
    public synchronized int trackedKeyCount() {
        return states.size();
    }

    private synchronized void release(String key, boolean exclusive) {
        int[] state = states.get(key);
        if (state == null) {
            return;
        }
        if (exclusive) {
            state[WRITER] = 0;
        } else if (state[READERS] > 0) {
            state[READERS]--;
        }
        removeIfIdle(key, state);
    }

    /** 归零就删条目：这是「不泄漏」的唯一出口，三个入口都要走一遍。 */
    private void removeIfIdle(String key, int[] state) {
        if (state[WRITER] == 0 && state[READERS] == 0) {
            states.remove(key);
        }
    }

    private final class Handle implements LockHandle {

        private final String key;
        private final boolean exclusive;
        private boolean released;

        private Handle(String key, boolean exclusive) {
            this.key = key;
            this.exclusive = exclusive;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public boolean exclusive() {
            return exclusive;
        }

        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            released = true;
            release(key, exclusive);
        }
    }
}
