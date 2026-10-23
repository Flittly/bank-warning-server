package com.yangtze.bankwarning.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨实例共享的中断登记表，存在 Redis 上。
 *
 * <p><b>键的设计</b>（两个键，各答一个问题）：
 * <ul>
 *   <li>{@code bw:task:run:<taskId>} = 正在跑它的实例名，TTL 默认 60s，跑的过程中每算完一条断面续期一次。
 *       键在 ⇒ 真有实例在跑；键不在 ⇒ 确实没人跑。这就是「谁在跑」的**全局唯一**答案。</li>
 *   <li>{@code bw:task:cancel:<taskId>} = "1"，用户是否已要求停止。TTL 比租约长得多，
 *       避免任务跑很久时取消意图自己过期。</li>
 * </ul>
 *
 * <p><b>为什么租约键要带 TTL</b>：内存表随 JVM 死亡自动消失，天生就知道进程死没死；
 * Redis 里的键不会自己消失，所以必须靠**续期**来维持「我还活着」的声明。
 * 停止续期 = 声明作废 = 等价于进程死亡。这是把「进程存活探测」翻译成「键过期」的那一步，
 * 也是整个迁移里唯一非直觉的地方。
 *
 * <p><b>为什么每个操作都是 Lua</b>：{@code EXISTS} 之后接 {@code SET} 是两步，
 * 两步之间任务可能刚好结束（或刚被别的实例接管），于是「查的时候有、写的时候没有」。
 * 脚本在 Redis 单线程里原子执行，这个缝隙就不存在了。
 *
 * <p><b>Redis 挂了怎么办</b>：读操作一律降级（{@link CancelSignal#CONTINUE} / {@code false}）并限流告警，
 * 让计算继续跑 —— 不能因为一个协调组件挂掉就把主业务打死。但 {@link #requestCancel} **必须抛异常**：
 * 它返回 {@code false} 的语义是「没人跑，可以直接改库收尾」，一旦把「Redis 连不上」
 * 降级成这个结论，就会重新制造出「误判僵尸 + 状态与事实反向」那个 bug。
 * 宁可让用户看到取消失败，也不能让它悄悄写错库。
 */
@Component
@ConditionalOnProperty(prefix = "bw.cluster", name = "enabled", havingValue = "true")
public class RedisTaskCancelRegistry implements TaskCancelRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskCancelRegistry.class);

    private static final String RUN_KEY_PREFIX = "bw:task:run:";
    private static final String CANCEL_KEY_PREFIX = "bw:task:cancel:";

    /** 登记：无条件接管租约，并清掉上一轮残留的取消标志（否则新一次运行刚起步就会自杀）。 */
    private static final DefaultRedisScript<Long> REGISTER_SCRIPT = script(
            "redis.call('set', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2])) "
            + "redis.call('del', KEYS[2]) "
            + "return 1");

    /**
     * 注销：只删自己的租约。
     * 若租约已被别的实例覆盖，什么都不动 —— 否则会把接管者的登记连同它的取消标志一起抹掉。
     */
    private static final DefaultRedisScript<Long> UNREGISTER_SCRIPT = script(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "  redis.call('del', KEYS[1]) "
            + "  redis.call('del', KEYS[2]) "
            + "  return 1 "
            + "end "
            + "return 0");

    /** 请求取消：只有租约还在（确实有人在跑）才置标志，返回值即 cooperative / stale 的判据。 */
    private static final DefaultRedisScript<Long> REQUEST_CANCEL_SCRIPT = script(
            "if redis.call('exists', KEYS[1]) == 1 then "
            + "  redis.call('set', KEYS[2], '1', 'EX', tonumber(ARGV[1])) "
            + "  return 1 "
            + "end "
            + "return 0");

    /**
     * 心跳检查：一次往返做完三件事。
     * 返回 -1 = 已失去所有权；1 = 被要求取消；0 = 继续。
     */
    private static final DefaultRedisScript<Long> HEARTBEAT_SCRIPT = script(
            "local owner = redis.call('get', KEYS[1]) "
            + "if owner ~= ARGV[1] then "
            + "  return -1 "
            + "end "
            + "redis.call('expire', KEYS[1], tonumber(ARGV[2])) "
            + "if redis.call('exists', KEYS[2]) == 1 then "
            + "  return 1 "
            + "end "
            + "return 0");

    private final StringRedisTemplate redis;
    private final String instanceId;
    private final Duration leaseTtl;
    private final Duration cancelTtl;

    /** 告警限流：断面循环每秒会问好几次，Redis 挂掉时不能每个断面都打一行堆栈。 */
    private final AtomicLong lastWarnAt = new AtomicLong(0L);

    public RedisTaskCancelRegistry(
            StringRedisTemplate redis,
            @Value("${bw.cluster.instance-id:}") String instanceId,
            @Value("${bw.cluster.lease-ttl:60s}") Duration leaseTtl,
            @Value("${bw.cluster.cancel-ttl:12h}") Duration cancelTtl) {
        this.redis = redis;
        this.instanceId = StringUtils.hasText(instanceId) ? instanceId.trim() : defaultInstanceId();
        this.leaseTtl = leaseTtl;
        this.cancelTtl = cancelTtl;
        log.info("[cancel-registry] redis 模式启用, instanceId={}, leaseTtl={}, cancelTtl={}",
                this.instanceId, leaseTtl, cancelTtl);
    }

    public String getInstanceId() {
        return instanceId;
    }

    @Override
    public void register(String taskId) {
        redis.execute(REGISTER_SCRIPT, keys(taskId), instanceId, String.valueOf(leaseTtl.getSeconds()));
        log.info("[cancel-registry] registered, taskId={}, owner={}", taskId, instanceId);
    }

    @Override
    public void unregister(String taskId) {
        try {
            Long removed = redis.execute(UNREGISTER_SCRIPT, keys(taskId), instanceId);
            if (removed == null || removed == 0L) {
                log.warn("[cancel-registry] unregister skipped, lease not owned by this instance, taskId={}, owner={}",
                        taskId, instanceId);
            }
        } catch (RuntimeException e) {
            // 注销失败只会留下一个 60s 内自然过期的键，不影响正确性 ⇒ 不向上抛，避免盖住真正的异常
            warnThrottled("[cancel-registry] unregister failed, lease will expire by ttl, taskId=" + taskId, e);
        }
    }

    @Override
    public boolean isRunning(String taskId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(runKey(taskId)));
        } catch (RuntimeException e) {
            warnThrottled("[cancel-registry] isRunning failed, treated as not-running, taskId=" + taskId, e);
            return false;
        }
    }

    @Override
    public boolean requestCancel(String taskId) {
        // 刻意不 catch：false 意味着「调用方可以直接改库收尾」，那是不能靠猜测得出的结论
        Long armed = redis.execute(REQUEST_CANCEL_SCRIPT, keys(taskId), String.valueOf(cancelTtl.getSeconds()));
        return armed != null && armed == 1L;
    }

    @Override
    public boolean isCancelRequested(String taskId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(cancelKey(taskId)));
        } catch (RuntimeException e) {
            warnThrottled("[cancel-registry] isCancelRequested failed, treated as false, taskId=" + taskId, e);
            return false;
        }
    }

    @Override
    public CancelSignal heartbeatAndCheck(String taskId) {
        try {
            Long result = redis.execute(HEARTBEAT_SCRIPT, keys(taskId),
                    instanceId, String.valueOf(leaseTtl.getSeconds()));
            if (result == null) {
                return CancelSignal.CONTINUE;
            }
            if (result == -1L) {
                return CancelSignal.LOST_OWNERSHIP;
            }
            if (result == 1L) {
                return CancelSignal.CANCEL_REQUESTED;
            }
            return CancelSignal.CONTINUE;
        } catch (RuntimeException e) {
            // 读路径降级为「继续算」：协调组件不可用不该让正在跑的任务集体失败。
            // 代价是这段时间里取消指令收不到（用户看到的是任务继续跑到结束），可接受且可观测。
            warnThrottled("[cancel-registry] heartbeat failed, continue without coordination, taskId=" + taskId, e);
            return CancelSignal.CONTINUE;
        }
    }

    private List<String> keys(String taskId) {
        return List.of(runKey(taskId), cancelKey(taskId));
    }

    private static String runKey(String taskId) {
        return RUN_KEY_PREFIX + taskId;
    }

    private static String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    /** 默认实例标识：主机名 + pid。同机多实例（8090 / 8091）也能区分开。 */
    private static String defaultInstanceId() {
        long pid = ProcessHandle.current().pid();
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + pid;
        } catch (UnknownHostException e) {
            return "bw-" + pid;
        }
    }

    private void warnThrottled(String message, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= 60_000L && lastWarnAt.compareAndSet(last, now)) {
            log.warn(message, e);
        }
    }

    private static DefaultRedisScript<Long> script(String lua) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }
}
