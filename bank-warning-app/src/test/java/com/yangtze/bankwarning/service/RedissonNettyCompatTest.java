package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时冒烟：验证 Redisson 3.52.0 能否跑在 Netty 4.2.7 上。
 *
 * <p>Redisson 3.52.0 自身声明依赖 netty-bom 4.1.127，而 Spring Boot 4.0.0 把 netty 统一
 * 仲裁到 4.2.7（netty-codec 在 4.2 被拆成 codec-base/compression/protobuf/marshalling）。
 * 跨大版本升级的最大风险是运行期 {@code NoSuchMethodError}，编译期看不出来，
 * 所以必须先真跑一次抢锁。
 */
class RedissonNettyCompatTest {

    @Test
    void redissonLockWorksOnNetty42() throws Exception {
        Assumptions.assumeTrue(redisReachable(), "本机未运行 Redis，跳过");

        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient client = Redisson.create(config);
        try {
            RLock lock = client.getLock("bw:test:netty-compat");
            client.getKeys().delete("bw:test:netty-compat");
            assertTrue(lock.tryLock(0, TimeUnit.SECONDS), "应当能拿到锁");
            lock.unlock();
        } finally {
            client.shutdown();
        }
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
