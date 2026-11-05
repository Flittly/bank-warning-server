package com.yangtze.bankwarning.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 断面失败原因的分类。
 *
 * <p>为什么钉这一组：模型失败时真正的解释（"DEM 在断面处全是 nodata"）是以错误码
 * 形式传上来的，一旦分类器认不出来就退化成 {@code UNKNOWN_ERROR}，前端只能显示
 * 「计算过程中发生未知错误」—— 用户看到的就是一句 404 加一句未知错误，完全不知道该
 * 去补 DEM 还是去改断面。下面这些码都是 sectionView.py 真实会抛出来的。
 */
class TaskExecutionServiceClassifyErrorTest {

    @Test
    void recognisesDemCoverageFailureInsteadOfUnknown() {
        Map<String, Object> info = TaskExecutionService.classifyError(new IllegalStateException(
                "Section View failed, section_id=sec-1, error_code=DEM_COVERAGE_INSUFFICIENT, "
                        + "error_message=DEM_COVERAGE_INSUFFICIENT: 0/100 usable sample points"));

        assertEquals("DEM_COVERAGE_INSUFFICIENT", info.get("error_code"));
    }

    @Test
    void recognisesDemCoverageFailureFromDependencyChainSummary() {
        // 预置 case 失败时上游只拿到「<case_id>: DemCoverageError: DEM_COVERAGE_INSUFFICIENT: ...」
        Map<String, Object> info = TaskExecutionService.classifyError(new IllegalStateException(
                "bc262309b5d937559712591d93f1bc3a: DemCoverageError: DEM_COVERAGE_INSUFFICIENT: "
                        + "the longest continuously valid stretch of this section is only 0.0 m"));

        assertEquals("DEM_COVERAGE_INSUFFICIENT", info.get("error_code"));
    }

    @Test
    void recognisesDegenerateAndEmptyProfiles() {
        assertEquals("SECTION_PROFILE_DEGENERATE", TaskExecutionService.classifyError(
                new IllegalStateException("SECTION_PROFILE_DEGENERATE: deepest point is the first sample"))
                .get("error_code"));
        assertEquals("SECTION_VERTICAL_PROFILE_EMPTY", TaskExecutionService.classifyError(
                new IllegalStateException("SECTION_VERTICAL_PROFILE_EMPTY: points_v is empty"))
                .get("error_code"));
    }

    @Test
    void demCodeWinsOverTextualGeometryHints() {
        // 这条消息同时含 "scatter points are insufficient"（历史规则会判成几何无效），
        // 但它其实是数据问题：错误码必须优先于文本特征。
        Map<String, Object> info = TaskExecutionService.classifyError(new IllegalStateException(
                "Section View failed, error_code=DEM_COVERAGE_INSUFFICIENT, "
                        + "error_message=Section scatter points are insufficient"));

        assertEquals("DEM_COVERAGE_INSUFFICIENT", info.get("error_code"));
    }

    @Test
    void keepsTheOldMappingsForNonDataFailures() {
        assertEquals("GEOMETRY_INVALID", TaskExecutionService.classifyError(
                new IllegalStateException("LineString Is Invalid")).get("error_code"));
        assertEquals("MODEL_TIMEOUT", TaskExecutionService.classifyError(
                new IllegalStateException("Model execution timed out")).get("error_code"));
        assertEquals("MODEL_UNAVAILABLE", TaskExecutionService.classifyError(
                new IllegalStateException("Connection refused")).get("error_code"));
        assertEquals("UNKNOWN_ERROR", TaskExecutionService.classifyError(
                new IllegalStateException("something nobody expected")).get("error_code"));
    }
}
