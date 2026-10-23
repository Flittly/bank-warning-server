package com.yangtze.bankwarning.service.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内读写锁的语义。重点是**归零即删**：{@code states} 是常驻 Map，
 * 每个任务的每次单算都会新增一个 key，泄漏就是慢性的内存增长 + 越来越慢的全局 monitor。
 */
class LocalClusterLockManagerTest {

    @Test
    void sharedLocksStack() {
        LocalClusterLockManager manager = new LocalClusterLockManager();

        ClusterLockManager.LockHandle first = manager.tryLockShared("k");
        ClusterLockManager.LockHandle second = manager.tryLockShared("k");

        assertNotNull(second, "共享模式应当可以重复获取");
        assertTrue(manager.isHeld("k"));

        first.close();
        assertTrue(manager.isHeld("k"), "还有一个共享持有者，不能算释放");

        second.close();
        assertFalse(manager.isHeld("k"));
    }

    @Test
    void exclusiveBlocksShared() {
        LocalClusterLockManager manager = new LocalClusterLockManager();

        ClusterLockManager.LockHandle exclusive = manager.tryLockExclusive("k");
        assertNotNull(exclusive);

        assertNull(manager.tryLockShared("k"));
        assertNull(manager.tryLockExclusive("k"));

        exclusive.close();
        assertNotNull(manager.tryLockShared("k"));
    }

    @Test
    void sharedBlocksExclusive() {
        LocalClusterLockManager manager = new LocalClusterLockManager();

        ClusterLockManager.LockHandle shared = manager.tryLockShared("k");
        assertNull(manager.tryLockExclusive("k"));

        shared.close();
        assertNotNull(manager.tryLockExclusive("k"));
    }

    @Test
    void keysAreIndependent() {
        LocalClusterLockManager manager = new LocalClusterLockManager();

        manager.tryLockExclusive("k1");
        assertNotNull(manager.tryLockExclusive("k2"));
    }

    @Test
    void releasedKeysDoNotLeak() {
        LocalClusterLockManager manager = new LocalClusterLockManager();

        for (int index = 0; index < 100; index++) {
            ClusterLockManager.LockHandle handle = manager.tryLockExclusive("k-" + index);
            handle.close();
        }

        // 失败路径（抢不到）也会 computeIfAbsent 出一个空条目，它同样必须被清掉。
        ClusterLockManager.LockHandle holder = manager.tryLockExclusive("busy");
        assertNull(manager.tryLockExclusive("busy"));
        holder.close();

        assertEquals(0, manager.trackedKeyCount(), "所有条目都该归零删除");
    }

    @Test
    void doubleCloseIsSafe() {
        LocalClusterLockManager manager = new LocalClusterLockManager();

        ClusterLockManager.LockHandle handle = manager.tryLockExclusive("k");
        handle.close();
        handle.close(); // 不能把计数减成负数，也不能影响别人的锁

        ClusterLockManager.LockHandle another = manager.tryLockExclusive("k");
        assertNotNull(another);
        assertEquals(1, manager.trackedKeyCount());
        another.close();
    }
}
