package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「补充岸段」时 bank_ids 的合并规则。
 *
 * <p>这三条不变量任一条错了，用户都会当场看到：补重了（同一岸段出现两次）、
 * 补的顺序把老岸段打乱（树里的顺序突然变了）、或者 replace 模式下悄悄留下本该移除的岸段。
 */
class BusinessStoreServiceMergeBankIdsTest {

    @Test
    void mergeKeepsExistingOrderThenAppendsNewOnes() {
        var merged = BusinessStoreService.mergeBankIds(
                List.of("b1", "b2"),
                List.of("b3", "b4"),
                false);

        assertEquals(List.of("b1", "b2", "b3", "b4"), merged.bankIds());
        assertEquals(List.of("b3", "b4"), merged.added());
        assertTrue(merged.removed().isEmpty());
    }

    @Test
    void mergeIsIdempotentForAlreadyLinkedBanks() {
        // 重复打开弹窗、重复提交同一批：added 必须为空，集合不变
        var merged = BusinessStoreService.mergeBankIds(
                List.of("b1", "b2"),
                List.of("b2", "b1"),
                false);

        assertEquals(List.of("b1", "b2"), merged.bankIds());
        assertTrue(merged.added().isEmpty());
    }

    @Test
    void mergeToleratesBlankAndNullIds() {
        var merged = BusinessStoreService.mergeBankIds(
                List.of("b1", "  ", "b1"),
                java.util.Arrays.asList("b2", null, " "),
                false);

        assertEquals(List.of("b1", "b2"), merged.bankIds());
        assertEquals(List.of("b2"), merged.added());
    }

    @Test
    void mergeOnEmptyExistingTaskStartsFromIncoming() {
        var merged = BusinessStoreService.mergeBankIds(List.of(), List.of("b7"), false);
        assertEquals(List.of("b7"), merged.bankIds());
        assertEquals(List.of("b7"), merged.added());
    }

    @Test
    void replaceDropsTheOnesNotListed() {
        var merged = BusinessStoreService.mergeBankIds(
                List.of("b1", "b2", "b3"),
                List.of("b2", "b9"),
                true);

        assertEquals(List.of("b2", "b9"), merged.bankIds());
        assertEquals(List.of("b9"), merged.added());
        assertEquals(List.of("b1", "b3"), merged.removed());
    }

    @Test
    void replaceWithEmptyListClearsEverything() {
        // 结果必须是空集合（写库时序列化成 '[]'），而不是 keep 旧值
        var merged = BusinessStoreService.mergeBankIds(List.of("b1"), List.of(), true);
        assertTrue(merged.bankIds().isEmpty());
        assertEquals(List.of("b1"), merged.removed());
    }
}
