package com.yangtze.bankwarning.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「风险段汇总」导出：把逐断面的风险等级聚合成**连续的同类风险段**，
 * 每段给出经纬度位置、长度（km）与占比。
 *
 * <p>为什么不是「每个断面一行」：断面是 2 km 一个的抽样点，逐行导出只能看到
 * 「这条断面是几级」，看不到「哪一段岸线是高风险、有多长、占多少」。风险段才是
 * 业务上要处置的对象，也是报告里唯一能直接落到「多少公里需要加固」的口径。
 *
 * <h2>聚合口径（唯一真源，前后端都不得另写一套）</h2>
 * <ol>
 *   <li><b>分组</b>：同一 {@code bank_id} 内聚合。{@code distance}（沿程里程，m）
 *       是岸段内里程，跨岸段会归零，所以必须先分岸段再排序。</li>
 *   <li><b>去重</b>：同一岸段同一里程只留一条断面（历史任务存在同岸段生成两遍造成的
 *       里程重复），取 {@code segment_index} 最小者，即最早生成的那条。</li>
 *   <li><b>分段</b>：按里程升序遍历，相邻且 {@code risk_level} 相同的断面并入同一段；
 *       等级一变就断开。</li>
 *   <li><b>边界</b>：段与段在「两条异级断面里程的中点」处切开（与地图岸段色带
 *       {@code buildRiskBandSpans} 的 Voronoi 口径一致）。岸段首段向左延伸半个
 *       名义间距，末段向右延伸半个名义间距；名义间距取该岸段相邻里程差的<b>中位数</b>，
 *       单断面岸段回退到全任务中位数。因此<b>各段首尾相接、长度之和 = 该岸段被断面
 *       覆盖的总长</b>，占比合计严格 100%。</li>
 * </ol>
 *
 * <p><b>名义间距只算「首末外延」，不影响段与段的交界</b>（交界永远是两个相邻断面的中点）。
 * 它优先取该岸段<b>全部断面</b>（不管算没算出结果）的里程差中位数 —— 抽样网格的疏密
 * 由断面本身决定，不能被「哪几条恰好算出了结果」带偏：实测某自由断面岸段 7 条断面
 * 只算出 3 条，若只用这 3 条估间距（55 km），一条断面会被算成代表 28.9 km 岸线；
 * 用全部 7 条估（8 km）才接近真实抽样密度。
 *   <li><b>位置</b>：起点/终点取该段首/末断面的垂足（断面与岸线的交点），而不是断面
 *       端点 —— 断面端点一个在河心、一个在岸上，用端点会让人误以为风险在河里。
 *       中心点取该段里程中点处那条断面的垂足；垂足缺失时回退到断面两点中点。</li>
 *   <li><b>占比分母</b>：只统计「已算出风险等级的断面所覆盖的长度」。未计算 / 计算
 *       失败的断面不参与分子也不参与分母，因此占比描述的是<b>已评估岸线</b>的构成，
 *       不是整条岸段的绝对长度。</li>
 * </ol>
 *
 * <h2>输出</h2>
 * 文件名固定 {@code 风险段汇总.csv}，全任务一份（不按经纬度分区切）。分区会把跨区的
 * 一段切成碎片，长度与占比都会失真；分区明细仍由 {@link PartitionSectionCsvService}
 * 负责，两者行集同源、用途不同。
 *
 * <p>字段规格（列名自带单位，与现有 CSV 的 {@code 沿程距离(m)} 风格一致）：
 * <pre>
 * 序号, 岸段ID, 岸段名称, 风险等级, 风险等级说明,
 * 起点经度, 起点纬度, 终点经度, 终点纬度, 中心经度, 中心纬度,
 * 起点里程(m), 终点里程(m), 长度(km),
 * 占本岸段比例(%), 占全任务比例(%), 断面数, 起止断面
 * </pre>
 * 经纬度 6 位小数（≈0.1 m，与明细 CSV 一致）；长度 3 位小数（m 级换算，不引入伪精度）；
 * 占比 2 位小数、取值 0~100、<b>不带 % 号</b>（列名已声明单位，纯数值便于 Excel / pandas
 * 直接求和透视）。
 */
@Service
public class RiskSegmentSummaryService {

    private static final Logger log = LoggerFactory.getLogger(RiskSegmentSummaryService.class);

    /** 汇总文件名。无分区前缀，因此不会被 {@code 分区N_断面详情.csv} 的序号解析吃掉。 */
    public static final String FILE_NAME = "风险段汇总.csv";

    /** 断面里程缺失 / 等级缺失的断面无法参与长度计算，上限只用于日志告警。 */
    private static final int WARN_SAMPLE_LIMIT = 5;

    private final JdbcTemplate jdbcTemplate;

    public RiskSegmentSummaryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 对外入口 ====================

    /** 一份汇总产出：文件名 + 风险段条数 + 参与统计的断面数。 */
    public record Summary(String fileName, int segmentCount, int sectionCount) {
        public boolean isEmpty() {
            return segmentCount == 0;
        }
    }

    /**
     * 查询任务风险数据，聚合成风险段，写到 {@code outDir/风险段汇总.csv}。
     *
     * <p>任何异常都**不向外抛**：汇总属于附加产出，写失败不能让已经跑完的报告失败。
     *
     * @return 没有可统计的断面时返回 {@link Summary#isEmpty()} 为 true 的空结果
     */
    public Summary write(String taskId, File outDir) {
        List<SectionPoint> points;
        try {
            points = querySectionPoints(taskId);
        } catch (Exception e) {
            log.warn("[risk-segment] 查询断面风险数据失败，跳过风险段汇总: {}", e.getMessage(), e);
            return new Summary(FILE_NAME, 0, 0);
        }
        if (points.isEmpty()) {
            log.info("[risk-segment] 任务 {} 无可用断面风险数据，跳过风险段汇总", taskId);
            return new Summary(FILE_NAME, 0, 0);
        }

        List<RiskSegment> segments = buildSegments(points, queryBankSpacings(taskId));
        if (segments.isEmpty()) {
            log.info("[risk-segment] 任务 {} 聚合后无风险段，跳过风险段汇总", taskId);
            return new Summary(FILE_NAME, 0, 0);
        }

        String csv = toCsv(segments);
        if (outDir != null) {
            try {
                if (!outDir.exists() && !outDir.mkdirs()) {
                    log.warn("[risk-segment] 无法创建输出目录: {}", outDir);
                }
                Files.writeString(new File(outDir, FILE_NAME).toPath(), csv, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("[risk-segment] 写入 {} 失败: {}", FILE_NAME, e.getMessage());
            }
        }
        log.info("[risk-segment] 风险段汇总已导出：{} 段（断面 {} 条）→ {}",
                segments.size(), points.size(), new File(outDir, FILE_NAME));
        return new Summary(FILE_NAME, segments.size(), points.size());
    }

    /** 只生成 CSV 文本（接口直出用），不落盘、不抛异常。 */
    public String renderCsv(String taskId) {
        List<SectionPoint> points = querySectionPoints(taskId);
        return toCsv(buildSegments(points, queryBankSpacings(taskId)));
    }

    /**
     * 各岸段的**抽样间距**（名义间距），基于该岸段<b>全部</b>断面的里程差中位数。
     *
     * <p>为什么不用「有结果的断面」算：抽样网格由断面本身决定，不能被「哪几条恰好算出了
     * 结果」带偏。实测某自由断面岸段 7 条断面只算出 3 条，用结果断面估出来的间距是 55 km，
     * 一条断面就被算成代表 28.9 km 岸线；用全部断面估是 8 km，才接近真实抽样密度。
     *
     * <p>查询失败时返回空 Map（调用方自动退回「参与断面」的间距），不影响主流程。
     */
    Map<String, Double> queryBankSpacings(String taskId) {
        String sql = "SELECT COALESCE(bank_id, '') AS bank_id, distance "
                + "FROM cross_sections "
                + "WHERE task_id = ? AND deleted_at IS NULL AND distance IS NOT NULL "
                + "ORDER BY bank_id, distance";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, taskId);
            Map<String, List<Double>> distancesByBank = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Double distance = number(row.get("distance"));
                if (distance == null) {
                    continue;
                }
                distancesByBank
                        .computeIfAbsent(blankToDefault(str(row.get("bank_id")), "未归属岸段"),
                                k -> new ArrayList<>())
                        .add(distance);
            }
            Map<String, Double> spacingByBank = new LinkedHashMap<>();
            distancesByBank.forEach((bank, distances) -> {
                List<Double> gaps = new ArrayList<>(distances.size());
                for (int i = 1; i < distances.size(); i++) {
                    double gap = distances.get(i) - distances.get(i - 1);
                    if (gap > 0.5d) {
                        gaps.add(gap);
                    }
                }
                spacingByBank.put(bank, median(gaps));
            });
            return spacingByBank;
        } catch (Exception e) {
            log.warn("[risk-segment] 查询岸段抽样间距失败，退回用参与断面估算: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== 数据查询 ====================

    /** 参与聚合的断面：已算出风险等级、有里程的那一批。 */
    public record SectionPoint(
            String sectionId,
            String sectionName,
            String bankId,
            String bankName,
            Integer segmentIndex,
            double distance,
            int riskLevel,
            Double lng,
            Double lat) { }

    /**
     * 取任务下用于聚合的断面。
     *
     * <p>行集与 {@code PartitionSectionCsvService.querySectionDetails} 同源（同一张
     * {@code bank_risk_results} + 同一套 {@code cross_sections.geom} 口径），只多了
     * {@code segment_index}（去重排序用）与里程。差异只有两点，都是聚合必需的：
     * <ul>
     *   <li>{@code DISTINCT ON (section_id) ... ORDER BY id DESC}：同一断面被重算过会留下
     *       多行有效结果（{@code recomputeSection} 只 INSERT 不删旧行），取最新一条，
     *       否则同一里程会被算两次、长度翻倍；</li>
     *   <li>只保留 {@code risk_level} 与 {@code distance} 均非空的断面 —— 没有等级就没有
     *       「哪个风险」，没有里程就算不出长度。</li>
     * </ul>
     */
    public List<SectionPoint> querySectionPoints(String taskId) {
        String sql = "WITH latest AS ("
                + "  SELECT DISTINCT ON (r.section_id) r.section_id, r.section_name, r.bank_id,"
                + "         r.risk_level, r.id"
                + "  FROM bank_risk_results r"
                + "  WHERE r.task_id = ? AND r.deleted_at IS NULL"
                + "  ORDER BY r.section_id, r.id DESC"
                + ")"
                + " SELECT l.section_id, "
                + "   COALESCE(cs.section_name, l.section_name) AS section_name, "
                + "   COALESCE(cs.bank_id, l.bank_id) AS bank_id, "
                + "   b.bank_name AS bank_name, "
                + "   cs.segment_index AS segment_index, "
                + "   cs.distance AS distance, "
                + "   l.risk_level AS risk_level, "
                + "   cs.vertical_foot_point::text AS foot_json, "
                + "   ST_X(ST_StartPoint(cs.geom)) AS start_lng, "
                + "   ST_Y(ST_StartPoint(cs.geom)) AS start_lat, "
                + "   ST_X(ST_EndPoint(cs.geom)) AS end_lng, "
                + "   ST_Y(ST_EndPoint(cs.geom)) AS end_lat "
                + " FROM latest l "
                + " LEFT JOIN cross_sections cs ON l.section_id = cs.section_id "
                + " LEFT JOIN banks b ON COALESCE(cs.bank_id, l.bank_id) = b.bank_id "
                + " ORDER BY bank_id, distance, segment_index";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, taskId);
        List<SectionPoint> points = new ArrayList<>(rows.size());
        int skipped = 0;
        List<String> skippedSample = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Double distance = number(row.get("distance"));
            Double level = number(row.get("risk_level"));
            String sectionId = str(row.get("section_id"));
            if (distance == null || level == null) {
                skipped++;
                if (skippedSample.size() < WARN_SAMPLE_LIMIT) {
                    skippedSample.add(sectionId);
                }
                continue;
            }
            int riskLevel = (int) Math.round(level);
            double[] coord = resolveCoordinate(row);
            points.add(new SectionPoint(
                    sectionId,
                    str(row.get("section_name")),
                    blankToDefault(str(row.get("bank_id")), "未归属岸段"),
                    blankToDefault(str(row.get("bank_name")), ""),
                    intOrNull(row.get("segment_index")),
                    distance,
                    riskLevel,
                    Double.isNaN(coord[0]) ? null : coord[0],
                    Double.isNaN(coord[1]) ? null : coord[1]));
        }
        if (skipped > 0) {
            log.warn("[risk-segment] 任务 {} 有 {} 条断面因缺里程或缺风险等级未计入风险段（示例: {}）",
                    taskId, skipped, String.join(", ", skippedSample));
        }
        return points;
    }

    /**
     * 断面在岸线上的代表点：优先垂足（断面与岸线交点），回退断面两端中点。
     * 解析不到返回 {@code {NaN, NaN}}，由调用方转成 null。
     */
    static double[] resolveCoordinate(Map<String, Object> row) {
        double[] foot = PartitionSectionCsvService.parseFootPoint(row.get("foot_json"));
        if (!Double.isNaN(foot[0]) && !Double.isNaN(foot[1])) {
            return foot;
        }
        Double startLng = number(row.get("start_lng"));
        Double startLat = number(row.get("start_lat"));
        Double endLng = number(row.get("end_lng"));
        Double endLat = number(row.get("end_lat"));
        if (startLng != null && startLat != null && endLng != null && endLat != null) {
            return new double[] {(startLng + endLng) / 2, (startLat + endLat) / 2};
        }
        if (startLng != null && startLat != null) {
            return new double[] {startLng, startLat};
        }
        return new double[] {Double.NaN, Double.NaN};
    }

    // ==================== 聚合（纯逻辑） ====================

    /**
     * 一段连续同类风险。
     *
     * @param startLng/startLat 段首断面垂足（NaN 表示缺失）
     * @param startM/endM       段边界里程（m），已含「异级断面中点」与首末半间距外延
     */
    public record RiskSegment(
            String bankId,
            String bankName,
            int riskLevel,
            double startLng,
            double startLat,
            double endLng,
            double endLat,
            double centerLng,
            double centerLat,
            double startM,
            double endM,
            int sectionCount,
            String sectionRange) {

        /** 段长（m）。边界已保证 {@code endM >= startM}。 */
        public double lengthM() {
            return Math.max(0d, endM - startM);
        }
    }

    /**
     * 把断面序列聚合成风险段。
     *
     * <p>返回结果按「岸段ID 自然序 → 段起点里程升序」排列，与 CSV 行序一致。
     */
    public static List<RiskSegment> buildSegments(List<SectionPoint> points) {
        return buildSegments(points, Map.of());
    }

    /**
     * 同 {@link #buildSegments(List)}，但名义间距可由调用方给出。
     *
     * @param bankSpacingHints 岸段 ID → 名义间距（m），通常来自该岸段全部断面的里程差中位数。
     *                         只影响首段左延 / 末段右延，不影响段与段的交界（永远取中点）。
     *                         缺失或 ≤0 时退回「参与聚合断面的间距」，再退回全任务中位数。
     */
    public static List<RiskSegment> buildSegments(List<SectionPoint> points,
                                                  Map<String, Double> bankSpacingHints) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        Map<String, Double> hints = bankSpacingHints == null ? Map.of() : bankSpacingHints;

        // 1) 按岸段分组
        Map<String, List<SectionPoint>> byBank = new LinkedHashMap<>();
        for (SectionPoint p : points) {
            byBank.computeIfAbsent(p.bankId(), k -> new ArrayList<>()).add(p);
        }

        // 2) 每组排序 + 按里程去重（保留 segment_index 最小 = 最早生成的那条）
        Map<String, List<SectionPoint>> cleaned = new LinkedHashMap<>();
        Map<String, Double> spacingByBank = new LinkedHashMap<>();
        List<Double> allGaps = new ArrayList<>();
        for (Map.Entry<String, List<SectionPoint>> entry : byBank.entrySet()) {
            List<SectionPoint> sorted = dedupeByDistance(entry.getValue());
            if (sorted.isEmpty()) {
                continue;
            }
            cleaned.put(entry.getKey(), sorted);
            List<Double> gaps = gapsOf(sorted);
            allGaps.addAll(gaps);
            spacingByBank.put(entry.getKey(), median(gaps));
        }
        double globalSpacing = median(allGaps);

        // 3) 切段 + 定边界
        List<RiskSegment> result = new ArrayList<>();
        for (Map.Entry<String, List<SectionPoint>> entry : cleaned.entrySet()) {
            List<SectionPoint> sorted = entry.getValue();
            double hint = hints.getOrDefault(entry.getKey(), 0d);
            double spacing = hint > 0
                    ? hint
                    : (spacingByBank.get(entry.getKey()) > 0
                            ? spacingByBank.get(entry.getKey())
                            : globalSpacing);
            List<List<SectionPoint>> runs = splitByRiskLevel(sorted);
            for (int i = 0; i < runs.size(); i++) {
                List<SectionPoint> run = runs.get(i);
                SectionPoint first = run.get(0);
                SectionPoint last = run.get(run.size() - 1);

                double startM;
                if (i == 0) {
                    startM = Math.max(0d, first.distance() - spacing / 2d);
                } else {
                    SectionPoint prevLast = runs.get(i - 1).get(runs.get(i - 1).size() - 1);
                    startM = (prevLast.distance() + first.distance()) / 2d;
                }
                double endM;
                if (i == runs.size() - 1) {
                    endM = last.distance() + spacing / 2d;
                } else {
                    SectionPoint nextFirst = runs.get(i + 1).get(0);
                    endM = (last.distance() + nextFirst.distance()) / 2d;
                }
                if (endM < startM) {
                    endM = startM;
                }

                SectionPoint center = nearestToMidpoint(run, (startM + endM) / 2d);
                result.add(new RiskSegment(
                        first.bankId(),
                        first.bankName(),
                        first.riskLevel(),   // 同一段内等级相同
                        coordinate(first.lng()),
                        coordinate(first.lat()),
                        coordinate(last.lng()),
                        coordinate(last.lat()),
                        coordinate(center.lng()),
                        coordinate(center.lat()),
                        startM,
                        endM,
                        run.size(),
                        sectionRange(first, last)));
            }
        }

        result.sort(Comparator
                .comparing(RiskSegment::bankId, RiskSegmentSummaryService::naturalCompare)
                .thenComparingDouble(RiskSegment::startM));
        return result;
    }

    /** 同一岸段内按里程升序排列，并按里程去重（保留 segment_index 最小者）。 */
    static List<SectionPoint> dedupeByDistance(List<SectionPoint> points) {
        List<SectionPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator
                .comparingDouble(SectionPoint::distance)
                .thenComparing(SectionPoint::segmentIndex,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SectionPoint::sectionId));
        // TreeMap 的键用「毫米取整」而不是原始 double：同一里程在不同批次里可能差 1e-9，
        // 用 double 当键会把它们当成两条，长度就翻倍了。
        TreeMap<Long, SectionPoint> unique = new TreeMap<>();
        for (SectionPoint p : sorted) {
            long key = Math.round(p.distance() * 1000d);
            unique.putIfAbsent(key, p);
        }
        return new ArrayList<>(unique.values());
    }

    /** 相邻里程差（只取 > 0.5 m 的，忽略重合点）。 */
    static List<Double> gapsOf(List<SectionPoint> sortedByDistance) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < sortedByDistance.size(); i++) {
            double gap = sortedByDistance.get(i).distance() - sortedByDistance.get(i - 1).distance();
            if (gap > 0.5d) {
                gaps.add(gap);
            }
        }
        return gaps;
    }

    /** 中位数；空集合返回 0（上层会把 0 当作「无名义间距」再回退）。 */
    static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        List<Double> copy = new ArrayList<>(values);
        copy.sort(Comparator.naturalOrder());
        int n = copy.size();
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2d;
    }

    /** 按风险等级切成连续段（相邻同等级合并）。 */
    static List<List<SectionPoint>> splitByRiskLevel(List<SectionPoint> sortedByDistance) {
        List<List<SectionPoint>> runs = new ArrayList<>();
        List<SectionPoint> current = null;
        for (SectionPoint p : sortedByDistance) {
            if (current == null || current.get(current.size() - 1).riskLevel() != p.riskLevel()) {
                current = new ArrayList<>();
                runs.add(current);
            }
            current.add(p);
        }
        return runs;
    }

    /** 段内离给定里程最近的断面（中心点用）。 */
    static SectionPoint nearestToMidpoint(List<SectionPoint> run, double targetM) {
        SectionPoint best = run.get(0);
        double bestDelta = Math.abs(best.distance() - targetM);
        for (SectionPoint p : run) {
            double delta = Math.abs(p.distance() - targetM);
            if (delta < bestDelta) {
                best = p;
                bestDelta = delta;
            }
        }
        return best;
    }

    /** 起止断面文案：{@code 断面98~断面99}；只有一条时只写一个名字。 */
    static String sectionRange(SectionPoint first, SectionPoint last) {
        String a = blankToDefault(first.sectionName(), first.sectionId());
        String b = blankToDefault(last.sectionName(), last.sectionId());
        return a.equals(b) ? a : a + "~" + b;
    }

    // ==================== CSV ====================

    /** 表头（顺序即列序，不可随意调整：下游脚本按列名读，不按序号读）。 */
    static final List<String> COLUMNS = List.of(
            "序号", "岸段ID", "岸段名称", "风险等级", "风险等级说明",
            "起点经度", "起点纬度", "终点经度", "终点纬度", "中心经度", "中心纬度",
            "起点里程(m)", "终点里程(m)", "长度(km)",
            "占本岸段比例(%)", "占全任务比例(%)", "断面数", "起止断面");

    /**
     * 生成 CSV 文本（UTF-8 BOM，Excel 双击不乱码）。
     *
     * <p>占比两列的分母都在这里现算：
     * <ul>
     *   <li>本岸段 = 该岸段所有风险段长度之和（因此同一岸段各行相加 = 100）；</li>
     *   <li>全任务 = 全部风险段长度之和（因此全表各行相加 = 100）。</li>
     * </ul>
     * 长度合计为 0（理论上不会，除非全部断面里程重合）时占比输出空串，不写 0 ——
     * 「无法计算」和「占 0%」是两件事。
     */
    public static String toCsv(List<RiskSegment> segments) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(joinEscaped(COLUMNS)).append('\n');
        if (segments == null || segments.isEmpty()) {
            return sb.toString();
        }

        Map<String, Double> bankTotals = new LinkedHashMap<>();
        double grandTotal = 0d;
        for (RiskSegment segment : segments) {
            bankTotals.merge(segment.bankId(), segment.lengthM(), Double::sum);
            grandTotal += segment.lengthM();
        }

        int index = 0;
        for (RiskSegment segment : segments) {
            index++;
            double bankTotal = bankTotals.getOrDefault(segment.bankId(), 0d);
            List<String> cells = new ArrayList<>(COLUMNS.size());
            cells.add(String.valueOf(index));
            cells.add(segment.bankId());
            cells.add(segment.bankName());
            cells.add(String.valueOf(segment.riskLevel()));
            cells.add(PartitionSectionCsvService.riskLabel((double) segment.riskLevel()));
            cells.add(num(segment.startLng()));
            cells.add(num(segment.startLat()));
            cells.add(num(segment.endLng()));
            cells.add(num(segment.endLat()));
            cells.add(num(segment.centerLng()));
            cells.add(num(segment.centerLat()));
            cells.add(meters(segment.startM()));
            cells.add(meters(segment.endM()));
            cells.add(kilometers(segment.lengthM()));
            cells.add(percent(segment.lengthM(), bankTotal));
            cells.add(percent(segment.lengthM(), grandTotal));
            cells.add(String.valueOf(segment.sectionCount()));
            cells.add(segment.sectionRange());
            sb.append(joinEscaped(cells)).append('\n');
        }
        return sb.toString();
    }

    private static String joinEscaped(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(PartitionSectionCsvService.escape(cells.get(i)));
        }
        return sb.toString();
    }

    /** 经纬度：6 位小数；缺失输出空串（与明细 CSV 一致，不写 0）。 */
    static String num(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    /** 里程：1 位小数（m）。 */
    static String meters(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 长度：3 位小数（km），即精确到 1 m。 */
    static String kilometers(double lengthM) {
        return String.format(Locale.ROOT, "%.3f", lengthM / 1000d);
    }

    /** 占比：2 位小数，0~100 的纯数值，不带 % 号；分母为 0 时输出空串。 */
    static String percent(double part, double total) {
        if (total <= 0d) {
            return "";
        }
        return String.format(Locale.ROOT, "%.2f", part / total * 100d);
    }

    // ==================== 内部工具 ====================

    private static double coordinate(Double value) {
        return value == null ? Double.NaN : value;
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 「岸段 2」排在「岸段 10」前面：逐段比较，数字段按数值比，其余按字典序。
     * 纯字典序会把 {@code bankwgs84_10} 排到 {@code bankwgs84_2} 之前，读起来像乱序。
     */
    static int naturalCompare(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        Matcher ma = Pattern.compile("\\d+|\\D+").matcher(a);
        Matcher mb = Pattern.compile("\\d+|\\D+").matcher(b);
        while (ma.find() && mb.find()) {
            String ta = ma.group();
            String tb = mb.group();
            boolean na = Character.isDigit(ta.charAt(0));
            boolean nb = Character.isDigit(tb.charAt(0));
            int cmp;
            if (na && nb) {
                cmp = Long.compare(Long.parseLong(ta), Long.parseLong(tb));
            } else {
                cmp = ta.compareTo(tb);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.compareTo(b);
    }
}
