package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨实例语义测试：两个 {@link RedisTaskCancelRegistry} 实例（node-a / node-b）共用同一个 Redis，
 * 模拟「任务在 A 上跑、取消请求被 nginx 轮到 B」的真实场景。
 *
 * <p>这组用例要证明的只有一件事：<b>B 能看到 A 的租约</b>。内存实现给不了这个，
 * 所以它是整个改造里唯一必须用真 Redis 才能验证的部分。
 *
 * <p>没有 Redis 时整类跳过（{@link Assumptions}），不会让没装 Redis 的机器上跑测试变红。
 */
class RedisTaskCancelRegistryTest {

    private static final List<String> createdTaskIds = new ArrayList<>();

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisTaskCancelRegistry nodeA;
    private static RedisTaskCancelRegistry nodeB;

    @BeforeAll
    static void setUp() {
        String host = env("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("REDIS_PORT", "6379"));
        Assumptions.assumeTrue(reachable(host, port),
                "本机没有可用的 Redis（" + host + ":" + port + "），跨实例语义测试跳过");

        factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);

        nodeA = new RedisTaskCancelRegistry(redis, "node-a", Duration.ofSeconds(60), Duration.ofHours(12));
        nodeB = new RedisTaskCancelRegistry(redis, "node-b", Duration.ofSeconds(60), Duration.ofHours(12));
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @AfterEach
    void cleanUp() {
        if (redis == null) {
            return;
        }
        for (String taskId : createdTaskIds) {
            redis.delete(List.of(runKey(taskId), cancelKey(taskId)));
        }
        createdTaskIds.clear();
    }

    @Test
    void register_thenOtherInstanceSeesRunning() {
        String taskId = newTaskId();
        assertFalse(nodeB.isRunning(taskId), "还没登记时，B 当然看不到");

        nodeA.register(taskId);

        assertTrue(nodeB.isRunning(taskId), "B 必须能看见 A 登记的租约 —— 这正是内存表做不到的那一步");
    }

    @Test
    void requestCancel_whenNobodyRuns_isStale() {
        String taskId = newTaskId();

        assertFalse(nodeB.requestCancel(taskId), "没有任何实例持租约 ⇒ 必须判 stale，让调用方改库收尾");
    }

    @Test
    void requestCancel_fromAnotherInstance_isCooperative() {
        String taskId = newTaskId();
        nodeA.register(taskId);

        assertTrue(nodeB.requestCancel(taskId), "A 正在跑 ⇒ B 必须返回 cooperative 而不是 stale");

        assertTrue(nodeA.isCancelRequested(taskId), "A 必须看得见 B 插的旗");
    }

    @Test
    void heartbeatAfterCancel_returnsCancelRequested() {
        String taskId = newTaskId();
        nodeA.register(taskId);
        nodeB.requestCancel(taskId);

        assertEquals(TaskCancelRegistry.CancelSignal.CANCEL_REQUESTED, nodeA.heartbeatAndCheck(taskId));
    }

    @Test
    void heartbeatRenewsLease_beyondOriginalTtl() throws InterruptedException {
        RedisTaskCancelRegistry shortLease =
                new RedisTaskCancelRegistry(redis, "node-a", Duration.ofSeconds(2), Duration.ofHours(1));
        String taskId = newTaskId();
        shortLease.register(taskId);

        // 每次间隔 1 秒，累计 3 秒 > 原始 TTL 2 秒。若续期没生效，第 3 次心跳就该判失去所有权了。
        for (int i = 0; i < 3; i++) {
            Thread.sleep(1000);
            assertEquals(TaskCancelRegistry.CancelSignal.CONTINUE, shortLease.heartbeatAndCheck(taskId),
                    "第 " + (i + 1) + " 次心跳应当仍然拥有租约");
        }

        assertTrue(nodeB.isRunning(taskId), "持续续期 ⇒ 租约一直在");
    }

    @Test
    void leaseExpiresWhenNoHeartbeat_thenCancelIsStale() throws InterruptedException {
        RedisTaskCancelRegistry shortLease =
                new RedisTaskCancelRegistry(redis, "node-a", Duration.ofSeconds(1), Duration.ofHours(1));
        String taskId = newTaskId();
        shortLease.register(taskId);

        Thread.sleep(1600);

        assertFalse(nodeB.isRunning(taskId), "没人续期 ⇒ 租约自然过期，等价于「进程已死」");
        assertFalse(nodeB.requestCancel(taskId), "过期后必须判 stale，而不是误判成还有人在跑");
    }

    @Test
    void heartbeatAfterTakeover_returnsLostOwnership() {
        String taskId = newTaskId();
        nodeA.register(taskId);
        nodeB.register(taskId);

        assertEquals(TaskCancelRegistry.CancelSignal.LOST_OWNERSHIP, nodeA.heartbeatAndCheck(taskId),
                "租约已被 B 覆盖 ⇒ A 必须停手，且不能写终态");
        assertEquals(TaskCancelRegistry.CancelSignal.CONTINUE, nodeB.heartbeatAndCheck(taskId),
                "接管者自己是所有权人，照常继续");
    }

    @Test
    void unregister_doesNotDeleteLeaseOwnedByAnother() {
        String taskId = newTaskId();
        nodeA.register(taskId);
        nodeB.register(taskId);

        nodeA.unregister(taskId);

        assertTrue(nodeB.isRunning(taskId), "A 注销时不能删掉 B 的租约（否则 B 的取消会误判成 stale）");
    }

    @Test
    void register_clearsResidualCancelFlag() {
        String taskId = newTaskId();
        nodeA.register(taskId);
        nodeB.requestCancel(taskId);
        assertTrue(nodeA.isCancelRequested(taskId));

        nodeA.register(taskId);

        assertFalse(nodeA.isCancelRequested(taskId), "重新开始一轮必须清掉上一轮的取消标志，否则新任务刚起步就会自杀");
    }

    @Test
    void unregister_removesBothKeys() {
        String taskId = newTaskId();
        nodeA.register(taskId);

        nodeA.unregister(taskId);

        assertFalse(nodeA.isRunning(taskId));
        assertFalse(nodeA.isCancelRequested(taskId));
    }

    private static String runKey(String taskId) {
        return "bw:task:run:" + taskId;
    }

    private static String cancelKey(String taskId) {
        return "bw:task:cancel:" + taskId;
    }

    private static String newTaskId() {
        String taskId = "task-test-" + UUID.randomUUID();
        createdTaskIds.add(taskId);
        return taskId;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
