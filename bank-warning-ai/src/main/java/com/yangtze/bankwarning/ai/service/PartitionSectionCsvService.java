package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.ai.workflow.PartitionBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 按经纬度分区把「断面详情」写成若干独立 CSV。
 *
 * <p>用途：报告按经纬度分成 N 个区时，<b>随报告一并导出</b> N 个 CSV，
 * 每个区一个文件（外加可选的「未落入分区」文件），与报告正文的分区小节一一对应。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li><b>归区规则与出图/报告同源</b>：直接调用
 *       {@link PartitionBounds#contains}（含边判定），不做第二套实现；</li>
 *   <li><b>行集与报告同源</b>：SQL 的 join / where 与
 *       {@code RiskDataTools.queryRiskData} 完全一致（同一张
 *       {@code bank_risk_results} + 同一套断面几何口径），否则
 *       「CSV 里 M 条」和报告里「分区断面 N 条」会对不上。</li>
 * </ol>
 */
@Service
public class PartitionSectionCsvService {

    private static final Logger log = LoggerFactory.getLogger(PartitionSectionCsvService.class);

    /** 分区 CSV 文件名：{@code 分区<序号>_断面详情.csv}，序号与正文「分区 N」一致（1 起）。 */
    static final String PARTITION_FILE_PREFIX = "分区";
    static final String PARTITION_FILE_SUFFIX = "_断面详情.csv";
    /** 坐标缺失 / 落在自定义边界之外的断面收在这里，不静默丢数据。 */
    static final String UNASSIGNED_FILE_NAME = "未落入分区_断面详情.csv";

    private static final String[] BASE_COLUMNS = {
            "断面ID", "断面名称", "沿程距离(m)", "岸段ID", "岸段名称",
            "风险等级", "风险等级说明", "垂足经度", "垂足纬度",
            "起点经度", "起点纬度", "终点经度", "终点纬度"
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper json = new ObjectMapper();

    public PartitionSectionCsvService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一个分区的 CSV 产出：序号、位置标签（与报告正文同文案）、文件名、断面数。 */
    public record PartitionCsv(int index, String label, String fileName, int sectionCount) { }

    /**
     * 本次导出结果。
     *
     * @param partitions       各分区 CSV（按分区顺序）
     * @param unassignedFile   未落入任何分区的断面文件名；无此类断面时为 null
     * @param unassignedCount  未落入任何分区的断面数
     */
    public record CsvExport(List<PartitionCsv> partitions, String unassignedFile, int unassignedCount) {

        public static CsvExport empty() {
            return new CsvExport(List.of(), null, 0);
        }

        public boolean isEmpty() {
            return partitions.isEmpty() && unassignedCount == 0;
        }

        public int fileCount() {
            return partitions.size() + (unassignedCount > 0 ? 1 : 0);
        }
    }

    /**
     * 写出各分区的断面详情 CSV。
     *
     * <p>任何异常都**不向外抛**：分区断面详情属于报告的附加产出，写失败不能让
     * 已经跑完 6 步的报告失败（调用方会拿到空结果，报告照常输出）。
     *
     * @param taskId     任务 ID
     * @param outDir     落盘目录（报告本次的输出目录，与图片同目录）
     * @param partitions 分区边界（取自出图工具回传的 {@code tiles[].bbox}）
     */
    public CsvExport writePartitionCsvs(String taskId, File outDir,
                                        List<Map<String, Object>> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return CsvExport.empty();
        }
        List<Map<String, Object>> rows;
        try {
            rows = querySectionDetails(taskId);
        } catch (Exception e) {
            log.warn("[csv] 查询断面详情失败，跳过分区 CSV 导出: {}", e.getMessage(), e);
            return CsvExport.empty();
        }
        if (rows.isEmpty()) {
            log.info("[csv] 任务 {} 无断面风险数据，跳过分区 CSV 导出", taskId);
            return CsvExport.empty();
        }

        List<String> indicatorKeys = collectIndicatorKeys(rows);
        List<List<Map<String, Object>>> buckets = new ArrayList<>();
        for (int i = 0; i < partitions.size(); i++) {
            buckets.add(new ArrayList<>());
        }
        List<Map<String, Object>> unassigned = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Double lng = number(row.get("start_lng"));
            Double lat = number(row.get("start_lat"));
            boolean hit = false;
            if (lng != null && lat != null) {
                for (int i = 0; i < partitions.size(); i++) {
                    if (PartitionBounds.contains(partitions.get(i), lng, lat)) {
                        buckets.get(i).add(row);
                        hit = true;
                    }
                }
            }
            if (!hit) {
                unassigned.add(row);
            }
        }

        if (outDir != null && !outDir.exists() && !outDir.mkdirs()) {
            log.warn("[csv] 无法创建输出目录: {}", outDir);
        }

        List<PartitionCsv> written = new ArrayList<>();
        for (int i = 0; i < partitions.size(); i++) {
            String fileName = PARTITION_FILE_PREFIX + (i + 1) + PARTITION_FILE_SUFFIX;
            String label = PartitionBounds.label(partitions.get(i));
            writeCsv(outDir, fileName, toCsv(buckets.get(i), indicatorKeys, label));
            written.add(new PartitionCsv(i + 1, label, fileName, buckets.get(i).size()));
        }

        String unassignedFile = null;
        if (!unassigned.isEmpty()) {
            unassignedFile = UNASSIGNED_FILE_NAME;
            writeCsv(outDir, unassignedFile, toCsv(unassigned, indicatorKeys, "未落入分区"));
        }

        log.info("[csv] 分区断面详情已导出：{} 个 CSV（断面 {} 条，其中未落入分区 {} 条）→ {}",
                written.size() + (unassignedFile == null ? 0 : 1), rows.size(), unassigned.size(), outDir);
        return new CsvExport(written, unassignedFile, unassigned.size());
    }

    /**
     * 查询任务下全部断面的详情。
     *
     * <p>join / where 与 {@code RiskDataTools.queryRiskData} 保持一致（同一行集），
     * 只是多取了几列用于 CSV；断面端点一律走 {@code cross_sections.geom}
     * （{@code banks.geom} 从不被写入，不要用它）。
     */
    List<Map<String, Object>> querySectionDetails(String taskId) {
        String sql = "SELECT r.section_id, "
                + "COALESCE(cs.section_name, r.section_name) AS section_name, "
                + "cs.distance AS distance, "
                + "COALESCE(cs.bank_id, r.bank_id) AS bank_id, "
                + "b.bank_name AS bank_name, "
                + "r.risk_level AS risk_level, "
                + "r.indicators::text AS indicators_json, "
                + "ST_X(ST_StartPoint(cs.geom)) AS start_lng, "
                + "ST_Y(ST_StartPoint(cs.geom)) AS start_lat, "
                + "ST_X(ST_EndPoint(cs.geom)) AS end_lng, "
                + "ST_Y(ST_EndPoint(cs.geom)) AS end_lat, "
                + "cs.vertical_foot_point::text AS foot_json "
                + "FROM bank_risk_results r "
                + "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id "
                + "LEFT JOIN banks b ON COALESCE(cs.bank_id, r.bank_id) = b.bank_id "
                + "WHERE r.task_id = ? AND r.deleted_at IS NULL "
                + "ORDER BY r.section_id";
        return jdbcTemplate.queryForList(sql, taskId);
    }

    // ========== 纯逻辑（可单测，不碰 DB / 文件系统） ==========

    /** 指标 JSON 的键并集（排序），作为 CSV 的动态列。 */
    static List<String> collectIndicatorKeys(List<Map<String, Object>> rows) {
        Set<String> keys = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            keys.addAll(parseIndicators(row.get("indicators_json")).keySet());
        }
        return new ArrayList<>(keys);
    }

    /** 生成 CSV 文本（含 UTF-8 BOM，Excel 直接双击不乱码）。 */
    static String toCsv(List<Map<String, Object>> rows, List<String> indicatorKeys, String partitionLabel) {
        List<String> headers = new ArrayList<>();
        headers.add("分区");
        headers.addAll(List.of(BASE_COLUMNS));
        headers.addAll(indicatorKeys);

        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(joinEscaped(headers)).append('\n');
        for (Map<String, Object> row : rows) {
            Map<String, Object> indicators = parseIndicators(row.get("indicators_json"));
            double[] foot = parseFootPoint(row.get("foot_json"));
            List<String> cells = new ArrayList<>();
            cells.add(partitionLabel);
            cells.add(str(row.get("section_id")));
            cells.add(str(row.get("section_name")));
            cells.add(str(row.get("distance")));
            cells.add(str(row.get("bank_id")));
            cells.add(str(row.get("bank_name")));
            cells.add(str(row.get("risk_level")));
            cells.add(riskLabel(number(row.get("risk_level"))));
            cells.add(num(foot[0]));
            cells.add(num(foot[1]));
            cells.add(num(number(row.get("start_lng"))));
            cells.add(num(number(row.get("start_lat"))));
            cells.add(num(number(row.get("end_lng"))));
            cells.add(num(number(row.get("end_lat"))));
            for (String key : indicatorKeys) {
                cells.add(flatten(indicators.get(key)));
            }
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
            sb.append(escape(cells.get(i)));
        }
        return sb.toString();
    }

    /** CSV 转义：含逗号 / 引号 / 换行时用双引号包裹，内部引号翻倍。 */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static Map<String, Object> parseIndicators(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equals(text)) {
            return Map.of();
        }
        if (!text.startsWith("{")) {
            // 不是对象（历史数据可能是裸标量）→ 归到 result 列，不丢信息
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("result", text);
            return single;
        }
        try {
            Map<String, Object> parsed = new ObjectMapper().readValue(
                    text, new TypeReference<Map<String, Object>>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 垂足点：GeoJSON Point 的 {@code coordinates:[lng,lat]}；解析不到返回 {@code {NaN,NaN}}。 */
    static double[] parseFootPoint(Object raw) {
        if (raw == null) {
            return new double[] {Double.NaN, Double.NaN};
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equals(text)) {
            return new double[] {Double.NaN, Double.NaN};
        }
        try {
            Map<String, Object> node = new ObjectMapper().readValue(
                    text, new TypeReference<Map<String, Object>>() { });
            Object coords = node.get("coordinates");
            if (coords instanceof List<?> list && list.size() >= 2) {
                Double lng = number(list.get(0));
                Double lat = number(list.get(1));
                if (lng != null && lat != null) {
                    return new double[] {lng, lat};
                }
            }
        } catch (Exception ignored) {
            // 落到下面的 NaN
        }
        return new double[] {Double.NaN, Double.NaN};
    }

    // ========== 内部工具 ==========

    private void writeCsv(File dir, String fileName, String content) {
        if (dir == null) {
            return;
        }
        try {
            Files.writeString(new File(dir, fileName).toPath(), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[csv] 写入 {} 失败: {}", fileName, e.getMessage());
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
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

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 坐标 6 位小数（缺失输出空串），与报告 Step 1 文本的坐标口径一致。 */
    private static String num(Double value) {
        return value == null || value.isNaN() ? "" : String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String flatten(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map || value instanceof List) {
            try {
                return new ObjectMapper().writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    static String riskLabel(Double level) {
        if (level == null) {
            return "";
        }
        return switch ((int) Math.round(level)) {
            case 4 -> "极高风险";
            case 3 -> "高风险";
            case 2 -> "一般风险";
            case 1 -> "低风险";
            default -> "未知";
        };
    }
}
