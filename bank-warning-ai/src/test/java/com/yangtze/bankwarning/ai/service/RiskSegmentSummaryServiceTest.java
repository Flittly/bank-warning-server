package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.service.RiskSegmentSummaryService.RiskSegment;
import com.yangtze.bankwarning.ai.service.RiskSegmentSummaryService.SectionPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「风险段汇总」聚合口径的契约测试。
 *
 * <p>这套口径一旦漂移，导出的表就会出现「长度加起来对不上岸段」「占比合计不是 100」
 * 这种最难查的问题，所以把每条规则都钉死：
 * <ol>
 *   <li>相邻同等级合并、异级断开；</li>
 *   <li>段边界落在两条异级断面的<b>里程中点</b>，首末段各外延半个名义间距；</li>
 *   <li>同岸段同里程的重复断面只算一次（否则长度翻倍）；</li>
 *   <li>不同岸段即使等级相同也不合并（里程各自归零，合并毫无意义）；</li>
 *   <li>占比在本岸段 / 全任务两个分母下都合计 100；</li>
 *   <li>CSV 的列名、单位、精度、BOM 与明细 CSV 同一风格。</li>
 * </ol>
 */
@DisplayName("风险段汇总（位置 / 长度 / 占比）")
class RiskSegmentSummaryServiceTest {

    private static SectionPoint p(String bank, double distance, int level) {
        return p(bank, distance, level, null, null);
    }

    private static SectionPoint p(String bank, double distance, int level, Integer segIndex,
                                  String sectionName) {
        return new SectionPoint(
                bank + "-S" + (int) distance,
                sectionName == null ? "断面" + (int) (distance / 1000) : sectionName,
                bank,
                "岸段-" + bank,
                segIndex,
                distance,
                level,
                120.0 + distance / 1_000_000d,
                32.0 + distance / 1_000_000d);
    }

    // ------------------------------------------------------------------
    // 1. 分段规则
    // ------------------------------------------------------------------

    @Test
    @DisplayName("相邻同等级合并成一段，等级一变就断开")
    void mergesAdjacentSameLevel() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 1), p("A", 2000, 1),
                p("A", 4000, 2), p("A", 6000, 2),
                p("A", 8000, 1)));

        assertEquals(3, segments.size());
        assertEquals(1, segments.get(0).riskLevel());
        assertEquals(2, segments.get(0).sectionCount());
        assertEquals(2, segments.get(1).riskLevel());
        assertEquals(2, segments.get(1).sectionCount());
        assertEquals(1, segments.get(2).riskLevel());
        assertEquals(1, segments.get(2).sectionCount());
    }

    @Test
    @DisplayName("段边界取异级断面的里程中点，首末段各外延半个名义间距")
    void segmentBoundariesFallOnMidpoints() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 1), p("A", 2000, 1),
                p("A", 4000, 2), p("A", 6000, 2),
                p("A", 8000, 1)));

        // 名义间距 2000 → 首段左边界 max(0, 0-1000)=0；右边界 (2000+4000)/2=3000
        assertEquals(0d, segments.get(0).startM(), 1e-9);
        assertEquals(3000d, segments.get(0).endM(), 1e-9);
        assertEquals(3000d, segments.get(0).lengthM(), 1e-9);

        // 中段两侧都是中点
        assertEquals(3000d, segments.get(1).startM(), 1e-9);
        assertEquals(7000d, segments.get(1).endM(), 1e-9);
        assertEquals(4000d, segments.get(1).lengthM(), 1e-9);

        // 末段右边界 = 末断面 + 半间距
        assertEquals(7000d, segments.get(2).startM(), 1e-9);
        assertEquals(9000d, segments.get(2).endM(), 1e-9);
    }

    @Test
    @DisplayName("各段首尾相接，长度之和 = 岸段被覆盖总长（不留缝、不重叠）")
    void segmentsTileTheBankCoverage() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 3), p("A", 2000, 3), p("A", 4000, 1),
                p("A", 6000, 4), p("A", 8000, 4), p("A", 10000, 2)));

        assertEquals(0d, segments.get(0).startM(), 1e-9);
        for (int i = 1; i < segments.size(); i++) {
            assertEquals(segments.get(i - 1).endM(), segments.get(i).startM(), 1e-9,
                    "第 " + i + " 段应与上一段首尾相接");
        }
        double total = segments.stream().mapToDouble(RiskSegment::lengthM).sum();
        // 覆盖范围 = 0 ~ (10000 + 半个间距 1000) = 11000
        assertEquals(11000d, total, 1e-9);
    }

    // ------------------------------------------------------------------
    // 2. 去重 / 跨岸段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同岸段同里程的重复断面只算一次，取最早生成（segment_index 最小）的那条")
    void dedupesSameDistance() {
        SectionPoint early = new SectionPoint("S-early", "断面旧", "A", "岸段-A", 10, 2000d, 1,
                120.0, 32.0);
        SectionPoint late = new SectionPoint("S-late", "断面新", "A", "岸段-A", 410, 2000d, 3,
                121.0, 33.0);

        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(
                List.of(p("A", 0, 1), late, p("A", 4000, 1), early));

        assertEquals(1, segments.size(), "去重后只剩一段，不应因为重复点被切成两段");
        assertEquals(3, segments.get(0).sectionCount(), "0 / 2000 / 4000 三条参与聚合");
        // 起止文案取首末断面；被保留的是 segment_index 最小的「断面旧」，
        // 它排在中间，所以段尾仍然是 4000 那条「断面4」。
        assertEquals("断面0~断面4", segments.get(0).sectionRange());

        // 直接验证「留谁」：同一里程两条，保留 segment_index 小的（先创建的那条）
        List<SectionPoint> deduped = RiskSegmentSummaryService.dedupeByDistance(List.of(late, early));
        assertEquals(1, deduped.size());
        assertEquals("S-early", deduped.get(0).sectionId());
        // 长度仍按 0~4000 的覆盖范围算，重复点没有把长度翻倍
        assertEquals(5000d, segments.get(0).lengthM(), 1e-9);
    }

    @Test
    @DisplayName("不同岸段即使等级相同也不合并")
    void doesNotMergeAcrossBanks() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 2), p("A", 2000, 2),
                p("B", 0, 2), p("B", 2000, 2)));

        assertEquals(2, segments.size());
        assertEquals("A", segments.get(0).bankId());
        assertEquals("B", segments.get(1).bankId());
    }

    @Test
    @DisplayName("岸段按 ID 自然序排列：_2 在 _10 之前，而不是字典序")
    void sortsBanksNaturally() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("bankwgs84_10", 0, 1),
                p("bankwgs84_2", 0, 1)));

        assertEquals("bankwgs84_2", segments.get(0).bankId());
        assertEquals("bankwgs84_10", segments.get(1).bankId());
    }

    // ------------------------------------------------------------------
    // 3. 单断面岸段 / 稀疏断面
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单断面岸段用全任务名义间距兜底，长度不为 0")
    void singleSectionBankFallsBackToGlobalSpacing() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 1), p("A", 2000, 1), p("A", 4000, 1),
                p("B", 7000, 3)));

        RiskSegment lone = segments.stream()
                .filter(s -> "B".equals(s.bankId())).findFirst().orElseThrow();
        assertEquals(2000d, lone.lengthM(), 1e-9, "两侧各外延半个 2000 的名义间距");
        assertEquals(6000d, lone.startM(), 1e-9);
        assertEquals(8000d, lone.endM(), 1e-9);
    }

    @Test
    @DisplayName("断面缺失（跳号）时边界仍落在两条相邻断面之间，不产生空洞")
    void handlesGapsBetweenSections() {
        // 2000 → 8000 中间缺了两条断面（被删或算失败）：名义间距取中位数仍是 2000，
        // 于是段边界落在 5000，两段首尾相接、没有空洞。
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 1), p("A", 2000, 1), p("A", 8000, 3), p("A", 10000, 3)));

        assertEquals(2, segments.size());
        assertEquals(5000d, segments.get(0).endM(), 1e-9);
        assertEquals(5000d, segments.get(1).startM(), 1e-9);
        assertEquals(11000d, segments.get(1).endM(), 1e-9, "末段右边界 = 10000 + 半个名义间距 1000");
    }

    // ------------------------------------------------------------------
    // 4. 位置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("起点/终点取首末断面坐标，中心点取最接近段中点的断面")
    void coordinatesFollowFirstLastAndCenter() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 2), p("A", 2000, 2), p("A", 4000, 2)));

        RiskSegment only = segments.get(0);
        // p() 造的经纬度 = 120 + distance/1e6
        assertEquals(120.0, only.startLng(), 1e-9);
        assertEquals(120.004, only.endLng(), 1e-9, "终点取末断面（distance=4000）的坐标");
        // 段范围 0~5000，中点 2500 → 最近的是 2000 那条
        assertEquals(120.002, only.centerLng(), 1e-9);
    }

    // ------------------------------------------------------------------
    // 5. CSV 契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CSV 表头顺序固定 18 列，单位写在列名里")
    void csvHeaderIsStable() {
        String csv = RiskSegmentSummaryService.toCsv(RiskSegmentSummaryService.buildSegments(
                List.of(p("A", 0, 1), p("A", 2000, 2))));

        List<String> expected = List.of(
                "序号", "岸段ID", "岸段名称", "风险等级", "风险等级说明",
                "起点经度", "起点纬度", "终点经度", "终点纬度", "中心经度", "中心纬度",
                "起点里程(m)", "终点里程(m)", "长度(km)",
                "占本岸段比例(%)", "占全任务比例(%)", "断面数", "起止断面");
        String header = csv.split("\n")[0].replace("\uFEFF", "");
        assertEquals(String.join(",", expected), header);
        assertEquals(expected, RiskSegmentSummaryService.COLUMNS);
    }

    @Test
    @DisplayName("CSV 带 BOM；经纬度 6 位、长度 3 位、占比 2 位且不带 % 号")
    void csvNumbersUseFixedPrecision() {
        String csv = RiskSegmentSummaryService.toCsv(RiskSegmentSummaryService.buildSegments(
                List.of(p("A", 0, 1), p("A", 2000, 2))));

        assertTrue(csv.startsWith("\uFEFF"), "Excel 双击不乱码必须带 UTF-8 BOM");
        String[] lines = csv.split("\n");
        String[] cells = lines[1].split(",");

        // 起点经度 / 纬度 6 位小数
        assertTrue(cells[5].matches("-?\\d+\\.\\d{6}"), "经度应 6 位小数，实际 " + cells[5]);
        assertTrue(cells[6].matches("-?\\d+\\.\\d{6}"), "纬度应 6 位小数，实际 " + cells[6]);
        // 长度 km 3 位小数
        assertTrue(cells[13].matches("\\d+\\.\\d{3}"), "长度应 3 位小数，实际 " + cells[13]);
        // 占比 2 位小数、纯数值
        assertTrue(cells[14].matches("\\d+\\.\\d{2}"), "占比应 2 位小数，实际 " + cells[14]);
        assertFalse(cells[14].contains("%"), "列名已带 (%), 单元格不应再写 % 号");
        assertEquals("1", cells[0], "序号从 1 起");
        assertEquals("低风险", cells[4]);
    }

    @Test
    @DisplayName("同一岸段各行占比合计 100，全表各行占比合计 100")
    void percentagesSumToOneHundred() {
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(List.of(
                p("A", 0, 1), p("A", 2000, 1), p("A", 4000, 3), p("A", 6000, 2),
                p("B", 0, 4), p("B", 2000, 1), p("B", 4000, 1)));

        String[] lines = RiskSegmentSummaryService.toCsv(segments).split("\n");
        double bankTotal = 0;
        double grandTotal = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",");
            grandTotal += Double.parseDouble(cells[15]);
            if ("A".equals(cells[1])) {
                bankTotal += Double.parseDouble(cells[14]);
            }
        }
        assertEquals(100.0, bankTotal, 0.05, "A 岸段各行占比应合计 100（容 0.05 舍入）");
        assertEquals(100.0, grandTotal, 0.05, "全表各行占比应合计 100（容 0.05 舍入）");
    }

    @Test
    @DisplayName("没有可统计断面时只输出表头，不抛异常")
    void emptyInputYieldsHeaderOnly() {
        assertEquals(1, RiskSegmentSummaryService.toCsv(List.of()).split("\n").length);
        assertEquals(1, RiskSegmentSummaryService.toCsv(null).split("\n").length);
        assertTrue(RiskSegmentSummaryService.buildSegments(List.of()).isEmpty());
        assertTrue(RiskSegmentSummaryService.buildSegments(null).isEmpty());
    }

    @Test
    @DisplayName("断面数 / 起止断面列可直接定位回明细表")
    void sectionRangePointsBackToDetailCsv() {
        List<SectionPoint> points = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            points.add(p("A", i * 2000, 2, i, "断面" + (i + 1)));
        }
        List<RiskSegment> segments = RiskSegmentSummaryService.buildSegments(points);

        assertEquals(1, segments.size());
        assertEquals("断面1~断面4", segments.get(0).sectionRange());
        assertEquals(4, segments.get(0).sectionCount());
    }

    // ------------------------------------------------------------------
    // 6. 名义间距由调用方给（该岸段全部断面，而不只是算出结果的断面）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("调用方给的名义间距只改首末外延，不改段与段的交界")
    void spacingHintOnlyAffectsOuterEdges() {
        List<SectionPoint> points = List.of(
                p("A", 0, 1), p("A", 2000, 1), p("A", 4000, 2));

        // 不给 hint：间距从相邻断面推得 2000
        RiskSegment noHint = RiskSegmentSummaryService.buildSegments(points).get(1);
        assertEquals(5000d, noHint.endM(), 1e-9);

        // 给 hint=1000（该岸段全部断面的真实抽样间距比结果断面更密）
        List<RiskSegment> hinted = RiskSegmentSummaryService.buildSegments(points, Map.of("A", 1000d));
        assertEquals(3000d, hinted.get(0).endM(), 1e-9, "交界永远取两条异级断面的中点，不受 hint 影响");
        assertEquals(3000d, hinted.get(1).startM(), 1e-9);
        assertEquals(4500d, hinted.get(1).endM(), 1e-9, "末段右延 = 半个 hint");
    }

    @Test
    @DisplayName("hint 为 0 或缺失时退回相邻断面间距，不会把段长算成 0")
    void zeroOrMissingHintFallsBack() {
        List<SectionPoint> points = List.of(p("A", 0, 1), p("A", 2000, 1));
        for (Map<String, Double> hints : List.of(Map.<String, Double>of(), Map.of("A", 0d))) {
            RiskSegment only = RiskSegmentSummaryService.buildSegments(points, hints).get(0);
            // 间距退回 2000：左边界 max(0, 0-1000)=0，右边界 2000+1000=3000
            assertEquals(3000d, only.endM(), 1e-9);
            assertEquals(3000d, only.lengthM(), 1e-9);
        }
    }
}
