package com.yangtze.bankwarning.service;

import com.yangtze.bankwarning.domain.exception.SectionRecomputeException;
import com.yangtze.bankwarning.service.cluster.ClusterLockManager;
import com.yangtze.bankwarning.service.cluster.LocalClusterLockManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单断面重算的准入闸门：与整任务运行之间的**共享 / 独占**关系。
 *
 * <p>这层关系存在的原因很具体：{@code runTask} 第一步会清空该任务全部旧结果，
 * 若此刻有一条单算正把新结果写进去，就会被这次清空顺手删掉；反过来，整任务在跑时
 * 若还允许单算，单算写入的行会被循环里后算的那条覆盖成「看起来没生效」。
 * 所以两个方向都要能问出「现在是不是有人在动这个任务」。
 *
 * <p>用 {@link LocalClusterLockManager} 跑：集群实现（Redis）的跨实例语义在
 * {@code RedissonClusterLockManagerTest} 里验证，这里只钉住编排逻辑本身。
 */
class SectionRecomputeGuardTest {

    private SectionRecomputeGuard newGuard() {
        return new SectionRecomputeGuard(new LocalClusterLockManager());
    }

    @Test
    void secondAcquireOnSameSectionIsRejected() {
        SectionRecomputeGuard guard = newGuard();

        ClusterLockManager.LockHandle first = guard.acquire("sec-1", "task-1");
        assertNotNull(first);

        SectionRecomputeException exception =
                assertThrows(SectionRecomputeException.class, () -> guard.acquire("sec-1", "task-1"));
        assertEquals("SECTION_RECOMPUTING", exception.getCode());

        first.close();
    }

    @Test
    void differentSectionsOfSameTaskAreIndependent() {
        SectionRecomputeGuard guard = newGuard();

        // 关键语义：共享锁允许多条不同断面同时单算。
        // 若这里退化成互斥锁，用户同时点两条断面的「重算」就会有一条无故 409。
        ClusterLockManager.LockHandle first = guard.acquire("sec-1", "task-1");
        ClusterLockManager.LockHandle second = guard.acquire("sec-2", "task-1");
        assertNotNull(second);

        first.close();
        second.close();
    }

    @Test
    void closingScopeReleasesBothLocks() {
        SectionRecomputeGuard guard = newGuard();

        ClusterLockManager.LockHandle scope = guard.acquire("sec-1", "task-1");
        assertTrue(guard.isTaskLocked("task-1"));
        scope.close();

        // 两把锁都要放掉：任务锁漏了会让该任务永远跑不了整任务，断面锁漏了会一直 409。
        assertFalse(guard.isTaskLocked("task-1"));
        assertNotNull(guard.acquire("sec-1", "task-1"));
    }

    @Test
    void scopeCloseIsIdempotent() {
        SectionRecomputeGuard guard = newGuard();

        ClusterLockManager.LockHandle scope = guard.acquire("sec-1", "task-1");
        scope.close();
        scope.close();

        assertNotNull(guard.acquire("sec-1", "task-1"));
    }

    @Test
    void taskExclusiveRejectsSectionRecompute() {
        SectionRecomputeGuard guard = newGuard();

        ClusterLockManager.LockHandle taskLock = guard.tryLockTaskExclusive("task-1");
        assertNotNull(taskLock);

        SectionRecomputeException exception =
                assertThrows(SectionRecomputeException.class, () -> guard.acquire("sec-1", "task-1"));
        assertEquals("TASK_RUNNING", exception.getCode());

        taskLock.close();
    }

    @Test
    void sectionRecomputeRejectsTaskExclusive() {
        SectionRecomputeGuard guard = newGuard();

        ClusterLockManager.LockHandle scope = guard.acquire("sec-1", "task-1");

        // 反向：有单算在途时整任务不能起跑（它会清空结果）。
        assertNull(guard.tryLockTaskExclusive("task-1"));

        scope.close();
        assertNotNull(guard.tryLockTaskExclusive("task-1"));
    }

    @Test
    void differentTasksDoNotBlockEachOther() {
        SectionRecomputeGuard guard = newGuard();

        ClusterLockManager.LockHandle task1 = guard.tryLockTaskExclusive("task-1");
        assertNotNull(task1);
        // 两个任务各跑各的，锁必须按 taskId 分片，不能退化成全局锁。
        assertNotNull(guard.tryLockTaskExclusive("task-2"));

        task1.close();
    }
}
