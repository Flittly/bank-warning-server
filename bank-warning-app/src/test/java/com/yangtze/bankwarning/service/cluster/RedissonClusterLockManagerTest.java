package com.yangtze.bankwarning.service.cluster;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨实例读写锁：两个 {@link RedissonClusterLockManager}（node-a / node-b）连同一个 Redis，
 * 模拟「A 在跑任务、B 收到同一个任务的请求」的真实场景。
 *
 * <p>要证明的三件事：
 * <ol>
 *   <li><b>互斥真的跨进程</b> —— 这是内存实现给不了的，也是整个 P2 的目的；</li>
 *   <li><b>watchdog 真的在续期</b> —— 长任务跑过锁的 TTL 之后，锁必须还在。
 *       这条最容易写错成固定 leaseTime 而「测试全绿、生产事故」；</li>
 *   <li><b>进程猝死后锁会自己消失</b> —— 续期一停，TTL 到期锁就没了，不会留下死锁。</li>
 * </ol>
 *
 * <p>没有 Redis 时整类跳过，不会让没装 Redis 的机器上跑测试变红。
 */
class RedissonClusterLockManagerTest {

    /** 故意调得很短（默认 30s），否则第 2/3 条要等半分钟。 */
    private static final long WATCHDOG_MS = 1500L;

    private static RedissonClient clientA;
    private static RedissonClient clientB;
    private static RedissonClusterLockManager nodeA;
    private static RedissonClusterLockManager nodeB;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(redisReachable(), "本机未运行 Redis，跳过跨实例用例");
        clientA = createClient();
        clientB = createClient();
        nodeA = new RedissonClusterLockManager(clientA, "node-a");
        nodeB = new RedissonClusterLockManager(clientB, "node-b");
    }

    @AfterAll
    static void tearDown() {
        if (clientA != null) {
            clientA.shutdown();
        }
        if (clientB != null) {
            clientB.shutdown();
        }
    }

    @Test
    void exclusiveIsVisibleAcrossInstances() {
        String key = key("exclusive");
        ClusterLockManager.LockHandle handle = nodeA.tryLockExclusive(key);
        try {
            assertNotNull(handle);
            // B 看见 A 的锁 —— 这正是内存实现做不到的那一件事。
            assertNull(nodeB.tryLockExclusive(key), "B 不该拿到 A 已独占的锁");
            assertNull(nodeB.tryLockShared(key), "独占期间共享也必须被挡住");
            assertTrue(nodeB.isHeld(key));
        } finally {
            handle.close();
        }
        ClusterLockManager.LockHandle afterRelease = nodeB.tryLockExclusive(key);
        assertNotNull(afterRelease, "释放后 B 应当能拿到");
        afterRelease.close();
    }

    @Test
    void sharedLocksCoexistAcrossInstances() {
        String key = key("shared");
        ClusterLockManager.LockHandle fromA = nodeA.tryLockShared(key);
        ClusterLockManager.LockHandle fromB = nodeB.tryLockShared(key);
        try {
            // 两条不同断面在两个实例上同时单算 —— 必须允许。
            assertNotNull(fromB, "共享模式跨实例应当可以共存");
            assertNull(nodeA.tryLockExclusive(key), "有人共享时独占必须失败");
        } finally {
            fromA.close();
            fromB.close();
        }
    }

    @Test
    void watchdogKeepsLockAliveBeyondItsTtl() throws InterruptedException {
        String key = key("watchdog");
        ClusterLockManager.LockHandle handle = nodeA.tryLockExclusive(key);
        try {
            assertNotNull(handle);
            // 睡过 2 个 TTL：若用了固定 leaseTime，此刻锁早已过期，B 就能抢到。
            Thread.sleep(WATCHDOG_MS * 2 + 500);
            assertNull(nodeB.tryLockExclusive(key),
                    "锁活过了 TTL 仍在持有 ⇒ 证明确实在被 watchdog 续期");
        } finally {
            handle.close();
        }
    }

    @Test
    void lockExpiresWhenOwnerDies() throws InterruptedException {
        String key = key("owner-dies");

        // 模拟实例猝死：拿锁之后**不 release**，直接关掉客户端 ⇒ watchdog 停止续期。
        RedissonClient doomed = createClient();
        RedissonClusterLockManager nodeC = new RedissonClusterLockManager(doomed, "node-c");
        assertNotNull(nodeC.tryLockExclusive(key));
        doomed.shutdown();

        // 等到 TTL 过去，锁必须自己消失，否则这个任务永远没人能接。
        Thread.sleep(WATCHDOG_MS * 2 + 500);
        ClusterLockManager.LockHandle takeover = nodeB.tryLockExclusive(key);
        assertNotNull(takeover, "所有者的续期停止后，锁应自动过期");
        takeover.close();
    }

    private static String key(String scenario) {
        return "bw:test:lock:" + scenario + ":" + System.nanoTime();
    }

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        config.setLockWatchdogTimeout(WATCHDOG_MS);
        return Redisson.create(config);
    }

    private static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 300);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
