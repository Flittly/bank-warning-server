package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出图取数口径的回归测试（2026-10-27）。
 *
 * <p>线上问题：用户把断面手工标成「无效」后，**AI 出图里那一段岸线仍然有颜色**。
 *
 * <p>根因不在配色，而在取数：岸段色带是按「断面管辖区间」逐段着色的
 * （Python 侧 {@code gradient.build_span_colors} 取最近断面定色）。上一版为了让
 * 无效断面不出现在图里，直接在 SQL 里 {@code AND manual_invalid = false} 把它们
 * **过滤掉** —— 断面行一缺席，它原本管辖的那一段就改由左右两个有效断面就近接管，
 * 于是被标为无效的岸段照样被邻居的颜色铺满。
 *
 * <p>正确做法是**保留行、把等级置空**：Python 的 {@code is_rendered_level(None)}
 * 为 False，整段不画，与页面「灰虚线、不着色」同口径。
 *
 * <p>这几条断言看着像在测字符串，实际钉的是「行必须留在结果集里」这一条协议：
 * 谁再次把它改成过滤条件，这里会立刻红。
 */
@DisplayName("出图风险取数口径")
class VisualizationServiceRiskDataQueryTest {

    private final VisualizationService service =
            new VisualizationService(null, new ObjectMapper());

    @Test
    @DisplayName("无效断面必须留在结果集里，只把等级置空")
    void invalidSectionsAreKeptWithNullLevel() {
        VisualizationService.RiskDataQuery query =
                service.buildRiskDataQuery("task-1", null);

        assertTrue(query.sql().contains(
                        "CASE WHEN COALESCE(cs.manual_invalid, false) THEN NULL ELSE r.risk_level END"),
                "无效断面未按「保留行 + 等级置空」处理：" + query.sql());
        assertTrue(query.sql().contains("COALESCE(cs.manual_invalid, false) as manual_invalid"),
                "缺少 manual_invalid 标记列，描述/日志无法区分「人工无效」与「未计算」");
    }

    @Test
    @DisplayName("不得再按 manual_invalid 过滤整行")
    void invalidSectionsAreNotFilteredOut() {
        VisualizationService.RiskDataQuery query =
                service.buildRiskDataQuery("task-1", null);

        assertFalse(query.sql().contains("AND COALESCE(cs.manual_invalid, false) = false"),
                "又变回过滤整行了 —— 无效断面的辖区会被邻居接管，图上照样有色：" + query.sql());
        assertFalse(query.sql().replace(" ", "").contains("manual_invalid=false"),
                "又变回过滤整行了：" + query.sql());
    }

    @Test
    @DisplayName("查询参数按 taskId → bankId 顺序绑定")
    void paramsAreBoundInOrder() {
        VisualizationService.RiskDataQuery both =
                service.buildRiskDataQuery("task-1", "bank-9");
        assertEquals(List.of("task-1", "bank-9"), both.params());
        assertTrue(both.sql().contains("AND r.task_id = ?"));
        assertTrue(both.sql().contains("AND cs.bank_id = ?"));

        VisualizationService.RiskDataQuery onlyTask =
                service.buildRiskDataQuery("task-1", null);
        assertEquals(List.of("task-1"), onlyTask.params());
        assertFalse(onlyTask.sql().contains("cs.bank_id = ?"));
    }
}
