package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单断面重算的在途登记表。
 *
 * <p>它存在的原因很具体：{@code runTask} 第一步会清空该任务全部旧结果，
 * 若此刻有一条单算正把新结果写进去，就会被这次清空顺手删掉；反过来，整任务在跑时
 * 若还允许单算，单算写入的行会被循环里后算的那条覆盖成「看起来没生效」。
 * 所以两个方向都要能问出「现在有没有在算」。
 */
class SectionRecomputeGuardTest {

    @Test
    void secondAcquireOnSameSectionIsRejected() {
        SectionRecomputeGuard guard = new SectionRecomputeGuard();

        assertTrue(guard.tryAcquire("sec-1", "task-1"));
        assertFalse(guard.tryAcquire("sec-1", "task-1"));
        assertEquals(1, guard.inFlightCount());
    }

    @Test
    void differentSectionsAreIndependent() {
        SectionRecomputeGuard guard = new SectionRecomputeGuard();

        assertTrue(guard.tryAcquire("sec-1", "task-1"));
        assertTrue(guard.tryAcquire("sec-2", "task-1"));
        assertEquals(2, guard.inFlightCount());
    }

    @Test
    void releaseAllowsRetry() {
        SectionRecomputeGuard guard = new SectionRecomputeGuard();

        assertTrue(guard.tryAcquire("sec-1", "task-1"));
        guard.release("sec-1");

        assertFalse(guard.hasInFlightForTask("task-1"));
        assertTrue(guard.tryAcquire("sec-1", "task-1"));
    }

    @Test
    void reportsInFlightByTask() {
        SectionRecomputeGuard guard = new SectionRecomputeGuard();

        assertTrue(guard.tryAcquire("sec-1", "task-1"));
        assertTrue(guard.hasInFlightForTask("task-1"));
        assertFalse(guard.hasInFlightForTask("task-2"));

        guard.release("sec-1");
        assertFalse(guard.hasInFlightForTask("task-1"));
    }

    @Test
    void blankTaskIdNeverMatches() {
        SectionRecomputeGuard guard = new SectionRecomputeGuard();

        // 没有 taskId 的断面理论上不该出现；即便出现，也不能让所有任务的整任务运行都被拦下
        assertTrue(guard.tryAcquire("sec-1", null));
        assertFalse(guard.hasInFlightForTask(null));
        assertFalse(guard.hasInFlightForTask(""));
        assertFalse(guard.hasInFlightForTask("   "));
    }
}
