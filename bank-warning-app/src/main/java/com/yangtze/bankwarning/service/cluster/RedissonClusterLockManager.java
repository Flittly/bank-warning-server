package com.yangtze.bankwarning.service.cluster;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的跨实例读写锁（{@code bw.cluster.enabled=true} 时启用）。
 *
 * <p>底层用 Redisson 的 {@code RReadWriteLock}：读锁落在 Redis 的一个 hash 里做计数
 * （所以同任务多条断面可以同时单算），写锁落在另一个 string 上做独占。
 *
 * <p><b>为什么绝不能传 leaseTime</b>（这是最容易踩死的一行）：
 * <pre>
 * tryLock(0, TimeUnit.SECONDS)                  // ✅ 没有 leaseTime ⇒ 走 watchdog 自动续期
 * tryLock(0, 30, TimeUnit.SECONDS)              // ❌ 30 秒后**无论任务是否还在跑**都会被强制释放
 * </pre>
 * 一个任务要跑几百个断面、几十分钟，固定 leaseTime 意味着跑到第 31 秒锁就没了，
 * 第二个实例立刻拿到锁开始跑同一个任务 —— 这正是加锁要防的事，却被 leaseTime 亲手放行。
 * Redisson 的 watchdog 会在后台每 {@code lockWatchdogTimeout/3} 续期一次，
 * 只要 JVM 还活着锁就一直有效；JVM 猝死后续不上，锁在 TTL 后自然消失，不会留下死锁。
 *
 * <p><b>fail-closed</b>：Redis 连不上时让异常直接冒泡（500），**不返回 null**。
 * 返回 null 的语义是「别人正持有」，用户会看到「任务正在运行中」的 409 —— 那是在撒谎；
 * 而此时根本没有任何实例在跑，用户被一句假话挡在门外，无从排查。
 */
@Component
@ConditionalOnProperty(prefix = "bw.cluster", name = "enabled", havingValue = "true")
public class RedissonClusterLockManager implements ClusterLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedissonClusterLockManager.class);

    private final RedissonClient redissonClient;
    private final String instanceId;

    public RedissonClusterLockManager(
            RedissonClient redissonClient,
            @Value("${bw.cluster.instance-id:}") String instanceId) {
        this.redissonClient = redissonClient;
        this.instanceId = instanceId;
    }

    @Override
    public LockHandle tryLockShared(String key) {
        return acquire(redissonClient.getReadWriteLock(key).readLock(), key, false);
    }

    @Override
    public LockHandle tryLockExclusive(String key) {
        return acquire(redissonClient.getReadWriteLock(key).writeLock(), key, true);
    }

    private LockHandle acquire(RLock lock, String key, boolean exclusive) {
        boolean acquired;
        try {
            // 只有两个参数：waitTime=0（不等待）、leaseTime 缺省（交给 watchdog）。
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抢锁被中断: " + key, exception);
        }
        if (!acquired) {
            log.info("[cluster-lock] busy, key={}, exclusive={}, instanceId={}",
                    key, exclusive, instanceId);
            return null;
        }
        log.info("[cluster-lock] acquired, key={}, exclusive={}, instanceId={}",
                key, exclusive, instanceId);
        return new Handle(lock, key, exclusive);
    }

    @Override
    public boolean isHeld(String key) {
        var readWriteLock = redissonClient.getReadWriteLock(key);
        return readWriteLock.readLock().isLocked() || readWriteLock.writeLock().isLocked();
    }

    private final class Handle implements LockHandle {

        private final RLock lock;
        private final String key;
        private final boolean exclusive;
        private boolean released;

        private Handle(RLock lock, String key, boolean exclusive) {
            this.lock = lock;
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

        /**
         * 释放必须校验持有者。两种会走到「不是我持有的锁」的场景：
         * <ol>
         *   <li>业务代码把 handle 传到了别的线程去关闭（Redisson 的锁是线程亲和的）；</li>
         *   <li>watchdog 因 Redis 抖动续期失败，锁已被别的实例接管。</li>
         * </ol>
         * 不加校验直接 {@code unlock()}，Redisson 会抛 {@code IllegalMonitorStateException}，
         * 而这个异常会从 finally 里冒出来把原本正常的返回值顶掉 —— 一个收尾动作失败
         * 不该改变业务结果。
         */
        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            released = true;
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.info("[cluster-lock] released, key={}, exclusive={}, instanceId={}",
                            key, exclusive, instanceId);
                } else {
                    log.warn("[cluster-lock] skip release, not owner anymore, key={}, instanceId={}",
                            key, instanceId);
                }
            } catch (RuntimeException exception) {
                // 释放失败只记日志：锁最终会因 watchdog 停止续期而过期，不会永久阻塞。
                log.warn("[cluster-lock] release failed, key={}, instanceId={}, message={}",
                        key, instanceId, exception.getMessage());
            }
        }
    }
}
