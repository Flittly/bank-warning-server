package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class VisualizationService {

    private static final Logger log = LoggerFactory.getLogger(VisualizationService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.visualization.script-dir:../bank-model-server}")
    private String scriptDir;

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    public VisualizationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成风险分布图
     */
    public Map<String, Object> generateRiskMap(String taskId, String bankId) {
        log.info("[viz] generating risk map, taskId={}, bankId={}", taskId, bankId);

        // 查询风险数据
        List<Map<String, Object>> riskData = queryRiskData(taskId, bankId);
        if (riskData.isEmpty()) {
            return Map.of("success", false, "error", "没有风险数据");
        }

        // 调用 Python 脚本
        String dataJson = toJson(riskData);
        String title = bankId != null ? "岸段风险分布图" : "任务风险分布图";
        return executePython("risk-map", dataJson, title);
    }

    /**
     * 生成冲淤热力图
     */
    public Map<String, Object> generateHeatmap(String sectionId, String taskId) {
        log.info("[viz] generating heatmap, sectionId={}, taskId={}", sectionId, taskId);

        // 查询剖面数据
        List<Map<String, Object>> profiles = queryProfiles(sectionId, taskId);
        if (profiles.isEmpty()) {
            return Map.of("success", false, "error", "没有剖面数据");
        }

        // 构建网格数据
        List<List<Double>> gridData = buildGridData(profiles);
        if (gridData.isEmpty()) {
            return Map.of("success", false, "error", "无法构建网格数据");
        }

        // 调用 Python 脚本
        String dataJson = toJson(gridData);
        String title = sectionId != null ? "断面冲淤热力图" : "任务冲淤热力图";
        return executePython("heatmap", dataJson, title);
    }

    /**
     * 生成断面对比图
     */
    public Map<String, Object> generateSectionComparison(String sectionId, String taskId) {
        log.info("[viz] generating section comparison, sectionId={}, taskId={}", sectionId, taskId);

        // 查询剖面数据
        List<Map<String, Object>> profiles = queryProfiles(sectionId, taskId);
        if (profiles.isEmpty()) {
            return Map.of("success", false, "error", "没有剖面数据");
        }

        // 构建断面数据
        List<Map<String, Object>> sections = buildSectionData(profiles);
        if (sections.isEmpty()) {
            return Map.of("success", false, "error", "无法构建断面数据");
        }

        // 调用 Python 脚本
        String dataJson = toJson(sections);
        String title = sectionId != null ? "断面对比图" : "任务断面对比图";
        return executePython("section", dataJson, title);
    }

    /**
     * 查询风险数据
     */
    private List<Map<String, Object>> queryRiskData(String taskId, String bankId) {
        StringBuilder sql = new StringBuilder(
                "SELECT r.section_id, cs.section_name, r.risk_level, " +
                "r.indicators->>'result' as risk_value, " +
                "b.bank_name, " +
                "ST_X(ST_StartPoint(cs.geom)) as start_lng, " +
                "ST_Y(ST_StartPoint(cs.geom)) as start_lat, " +
                "ST_X(ST_EndPoint(cs.geom)) as end_lng, " +
                "ST_Y(ST_EndPoint(cs.geom)) as end_lat " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.deleted_at IS NULL"
        );

        List<Object> params = new ArrayList<>();
        if (taskId != null) {
            sql.append(" AND r.task_id = ?");
            params.add(taskId);
        }
        if (bankId != null) {
            sql.append(" AND cs.bank_id = ?");
            params.add(bankId);
        }
        sql.append(" ORDER BY r.risk_level DESC");

        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /**
     * 查询剖面数据
     */
    private List<Map<String, Object>> queryProfiles(String sectionId, String taskId) {
        if (sectionId != null) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM section_profiles WHERE section_id = ? AND deleted_at IS NULL ORDER BY created_at",
                    sectionId
            );
        }
        if (taskId != null) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM section_profiles WHERE task_id = ? AND deleted_at IS NULL ORDER BY section_id, created_at",
                    taskId
            );
        }
        return Collections.emptyList();
    }

    /**
     * 构建网格数据
     */
    @SuppressWarnings("unchecked")
    private List<List<Double>> buildGridData(List<Map<String, Object>> profiles) {
        List<List<Double>> gridData = new ArrayList<>();
        for (Map<String, Object> profile : profiles) {
            String profileDataJson = profile.get("profile_data") != null ?
                    profile.get("profile_data").toString() : "[]";
            try {
                Map<String, Object> profileData = objectMapper.readValue(profileDataJson, Map.class);
                List<List<Double>> points = (List<List<Double>>) profileData.get("points");
                if (points != null && !points.isEmpty()) {
                    List<Double> elevation = new ArrayList<>();
                    for (List<Double> point : points) {
                        if (point.size() >= 3) {
                            elevation.add(point.get(2));
                        }
                    }
                    gridData.add(elevation);
                }
            } catch (JsonProcessingException e) {
                log.warn("[viz] parse profile data failed", e);
            }
        }
        return gridData;
    }

    /**
     * 构建断面数据
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildSectionData(List<Map<String, Object>> profiles) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Map<String, Object> profile : profiles) {
            String profileDataJson = profile.get("profile_data") != null ?
                    profile.get("profile_data").toString() : "[]";
            try {
                Map<String, Object> profileData = objectMapper.readValue(profileDataJson, Map.class);
                List<List<Double>> points = (List<List<Double>>) profileData.get("points");
                if (points != null && !points.isEmpty()) {
                    List<Double> xCoords = new ArrayList<>();
                    List<Double> elevation = new ArrayList<>();
                    for (List<Double> point : points) {
                        if (point.size() >= 3) {
                            xCoords.add(point.get(0));
                            elevation.add(point.get(2));
                        }
                    }

                    Map<String, Object> section = new LinkedHashMap<>();
                    section.put("section_name", profile.get("section_name"));
                    section.put("x_coords", xCoords);
                    section.put("elevation", elevation);
                    section.put("deepest_index", profile.get("deepest_index"));
                    section.put("slope_foot_index", profile.get("slope_foot_index"));
                    section.put("timepoint", profile.get("timepoint"));
                    sections.add(section);
                }
            } catch (JsonProcessingException e) {
                log.warn("[viz] parse profile data failed", e);
            }
        }
        return sections;
    }

    /**
     * 执行 Python 脚本
     */
    private Map<String, Object> executePython(String command, String dataJson, String title) {
        try {
            // 写入临时数据文件
            String dataFile = outputDir + "/temp_data.json";
            new File(outputDir).mkdirs();
            try (FileWriter writer = new FileWriter(dataFile)) {
                writer.write(dataJson);
            }

            // 获取 bank-model-server 的绝对路径
            File scriptBase = new File(scriptDir).getAbsoluteFile();
            if (!scriptBase.exists()) {
                scriptBase = new File(System.getProperty("user.dir"), scriptDir).getAbsoluteFile();
            }
            
            // 构建命令 - 使用 uv run
            List<String> cmd = List.of(
                    "uv", "run", "python", "-m", "visualization.main",
                    command,
                    "--data", new File(dataFile).getAbsolutePath(),
                    "--title", title,
                    "--output", new File(outputDir).getAbsolutePath()
            );

            log.info("[viz] executing: {}", String.join(" ", cmd));
            log.info("[viz] working dir: {}", scriptBase.getAbsolutePath());

            // 执行命令 - 设置工作目录为 bank-model-server
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(scriptBase);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = readProcessOutput(process);
            boolean completed = process.waitFor(60, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return Map.of("success", false, "error", "Python 脚本执行超时");
            }

            log.info("[viz] Python output: {}", output);

            if (process.exitValue() != 0) {
                log.error("[viz] Python failed with exit code {}: {}", process.exitValue(), output);
                return Map.of("success", false, "error", "Python 脚本执行失败: " + output);
            }

            // 解析结果 - 尝试从输出中提取 JSON
            String jsonOutput = extractJson(output);
            if (jsonOutput == null) {
                log.warn("[viz] No JSON found in output, using raw output");
                return Map.of("success", false, "error", "Python 输出无有效 JSON: " + output);
            }
            
            return objectMapper.readValue(jsonOutput, Map.class);

        } catch (Exception e) {
            log.error("[viz] execute failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 从输出中提取 JSON 字符串
     */
    private String extractJson(String output) {
        if (output == null || output.isBlank()) return null;
        
        // 查找第一个 { 的位置
        int start = output.indexOf('{');
        if (start == -1) return null;
        
        // 从后往前找最后一个 }
        int end = output.lastIndexOf('}');
        if (end == -1 || end <= start) return null;
        
        return output.substring(start, end + 1);
    }
}
