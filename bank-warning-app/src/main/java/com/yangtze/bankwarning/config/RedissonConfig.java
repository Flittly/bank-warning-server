package com.yangtze.bankwarning.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 集群模式下的 {@link RedissonClient}（{@code bw.cluster.enabled=true} 才装配）。
 *
 * <p><b>为什么手写这个 Bean 而不用 {@code redisson-spring-boot-starter}</b>：
 * starter 的自动配置里会注册 {@code RedissonConnectionFactory} 并接管
 * {@code RedisConnectionFactory}，而我们已经在用 {@code spring-boot-starter-data-redis}
 * 提供的 Lettuce 工厂（取消标志那套 {@code StringRedisTemplate} 依赖它）。
 * 两套客户端抢同一个 Bean 类型，在 Spring Boot 4 上会互相覆盖、启动报重复定义；
 * 只引核心包 + 手写一个 Bean，两者各管各的连接，互不干扰：
 * <ul>
 *   <li>Lettuce（懒连接）→ 取消标志的租约/取消键，走手写 Lua；</li>
 *   <li>Redisson（常驻连接）→ 分布式锁，需要 watchdog 续期。</li>
 * </ul>
 *
 * <p><b>注意这是唯一会在启动期就建立 TCP 连接的地方</b>：Lettuce 是懒的，
 * 但 {@code Redisson.create()} 会立刻连。所以「开了开关但 Redis 没起」=
 * 启动直接失败，而不是运行到一半才发现。这是刻意的：集群模式没有 Redis 本来就不该起来。
 */
@Configuration
@ConditionalOnProperty(prefix = "bw.cluster", name = "enabled", havingValue = "true")
public class RedissonConfig {

    /**
     * watchdog 的基准 TTL。Redisson 会在它的 1/3 处（默认 10s）自动续期一次，
     * 所以这个值决定了「JVM 猝死后锁多久自动消失」—— 也是任务被误判成「还有人在跑」的最长窗口。
     * 默认 30s 与 Redisson 官方默认一致，不需要改。
     */
    @Value("${bw.cluster.lock-watchdog-timeout:30s}")
    private Duration lockWatchdogTimeout;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.timeout:2s}") Duration timeout) {

        Config config = new Config();
        // 复用 spring.data.redis.*，避免同一个 Redis 出现两处地址配置、改一处漏一处。
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectTimeout(Math.toIntExact(timeout.toMillis()))
                .setTimeout(Math.toIntExact(timeout.toMillis()));
        if (StringUtils.hasText(password)) {
            server.setPassword(password);
        }
        config.setLockWatchdogTimeout(lockWatchdogTimeout.toMillis());
        return Redisson.create(config);
    }
}
