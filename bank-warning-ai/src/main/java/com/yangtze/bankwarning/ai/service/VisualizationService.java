package com.yangtze.bankwarning.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class VisualizationService {

    private static final Logger log = LoggerFactory.getLogger(VisualizationService.class);

    /** 单次批量出图的岸段数上限：每张图都要起一个 Python 进程，防止把机器打满 */
    private static final int MAX_BATCH_BANKS = 50;

    /** Python 出图进程的基准超时（秒）。分幅出图时按子图数等比放大，封顶 600s。 */
    private static final int PYTHON_TIMEOUT_SECONDS = 60;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.visualization.script-dir:../bank-model-server}")
    private String scriptDir;

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    // ------------------------------------------------------------------
    // 遥感影像底图（画在 DEM / 岸段 / 断面之下的最底层，不改变这三层的任何参数）
    //
    // 合规：只允许天地图（国家地理信息公共服务平台，NASG）或自建 / 本地瓦片；
    //       Google、Bing(海外)、Mapbox、OpenStreetMap 直连瓦片等境外源不得使用。
    // key：由环境变量 TIANDITU_TK 或配置项提供，**代码与仓库内不含任何真实 key**，
    //      也不会写进交给 Python 的载荷（见 executePython 注释）。
    // ------------------------------------------------------------------
    @Value("${app.ai.visualization.basemap.enabled:auto}")
    private String basemapEnabled;

    @Value("${app.ai.visualization.basemap.provider:tianditu}")
    private String basemapProvider;

    @Value("${app.ai.visualization.basemap.layer:img}")
    private String basemapLayer;

    // key 解析优先级：配置项 app.ai.visualization.basemap.key > 环境变量 TIANDITU_TK > 空。
    // 嵌套占位符 ${TIANDITU_TK:} 由 Spring 直接落到 systemEnvironment 属性源，
    // 这样「只设系统环境变量、不改 yml」也能让 auto 模式判定有 key（与 Python 侧
    // resolve_config 的回退顺序一致）。application.yml 在 git 跟踪范围内，
    // 真实 key 建议走环境变量，不要提交进仓库。
    @Value("${app.ai.visualization.basemap.key:${TIANDITU_TK:}}")
    private String basemapKey;

    /** 0 表示按成图分辨率自适应缩放级 */
    @Value("${app.ai.visualization.basemap.zoom:0}")
    private int basemapZoom;

    @Value("${app.ai.visualization.basemap.alpha:1.0}")
    private double basemapAlpha;

    /** 底图启用与否只提示一次，避免按岸段批量出图时刷屏 */
    private static volatile boolean basemapNoticeLogged = false;

    private static volatile String currentTaskOutputDir;

    public static void beginTask(String taskOutputDir) {
        currentTaskOutputDir = taskOutputDir;
        new java.io.File(taskOutputDir).mkdirs();
    }

    public static void endTask() {
        currentTaskOutputDir = null;
    }

    public static String getCurrentTaskOutputDir() {
        return currentTaskOutputDir;
    }

    private String resolveOutputDir() {
        return currentTaskOutputDir != null ? currentTaskOutputDir : outputDir;
    }

    public VisualizationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成风险分布图（任务粒度：全任务断面合并为一张图）。
     */
    public Map<String, Object> generateRiskMap(String taskId, String bankId) {
        return generateRiskMap(taskId, bankId, null, null, null);
    }

    /**
     * 生成风险分布图，支持按经纬度分幅。
     *
     * @param splitRows  纬度方向分块数（1 = 不分块）
     * @param splitCols  经度方向分块数（1 = 不分块）
     * @param splitTiles 逐幅自定义边界的 JSON 数组字符串，形如
     *                   [{"row":0,"col":1,"west":119.45}, ...]；缺省的边沿用均分网格的值。
     *                   三段均为空 / null 时不下发 split，Python 走原单图路径（历史行为）。
     */
    public Map<String, Object> generateRiskMap(String taskId, String bankId,
                                               Integer splitRows, Integer splitCols,
                                               String splitTiles) {
        // 图名统一为「岸段风险分布图」：任务级与岸段级画的是同一套岸段色带，
        // 岸段级再在标题后追加「 · 岸段名」以区分（见 generateRiskMapBatch）。
        String title = "岸段风险分布图";
        return generateRiskMapTo(taskId, bankId, resolveOutputDir(), null, title,
                splitRows, splitCols, splitTiles);
    }

    /**
     * 批量出图（不分幅的历史入口；等价于 splitRows/splitCols/splitTiles 全为空）。
     */
    public Map<String, Object> generateRiskMapBatch(String taskId, String mode, List<String> bankIds) {
        return generateRiskMapBatch(taskId, mode, bankIds, null, null, null);
    }

    /**
     * 批量出图（分幅版）：在 merged / per_bank 任一模式下，每张图都可再按经纬度
     * 切成 rows×cols 幅子图（splitRows/splitCols/splitTiles 语义见
     * {@link #generateRiskMap(String, String, Integer, Integer, String)}）。
     *
     * <p>分幅时每个返回项携带多幅子图：entry.images[]（每幅 url/fileName/row/col/
     * bbox/sections_in_tile）+ entry.tileCount，entry.url 为首幅（旧前端只读 url 也正常）。
     * 统计口径：total/succeeded 仍按「张」计；imageTotal/imageSucceeded 按子图计。
     *
     * <p>分幅参数非法时 fail-fast：直接返回 error，不起任何 Python 进程。
     */
    public Map<String, Object> generateRiskMapBatch(String taskId, String mode, List<String> bankIds,
                                                    Integer splitRows, Integer splitCols,
                                                    String splitTiles) {
        if (taskId == null || taskId.isBlank()) {
            return Map.of("success", false, "error", "taskId 不能为空");
        }
        // fail-fast：rows/cols < 1、tiles 非合法 JSON 数组时直接返回，
        // 避免起了批量进程后才在每一张上报同样的错。
        Map<String, Object> splitConfig;
        try {
            splitConfig = buildSplitConfig(splitRows, splitCols, splitTiles);
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
        if (splitConfig != null) {
            log.info("[viz] batch 分幅出图 rows={} cols={} customTiles={}",
                    splitConfig.get("rows"), splitConfig.get("cols"),
                    splitConfig.getOrDefault("tiles", "无"));
        }
        boolean perBank = "per_bank".equalsIgnoreCase(String.valueOf(mode));
        String dirName = "viz_" + safeName(taskId) + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String dir = new File(outputDir, dirName).getAbsolutePath();
        new File(dir).mkdirs();
        log.info("[viz] batch start taskId={} mode={} dir={}", taskId, perBank ? "per_bank" : "merged", dir);

        List<Map<String, Object>> images = new ArrayList<>();
        if (!perBank) {
            String fileName = "risk_distribution_map.png";
            images.add(imageEntry(null, null, dirName, fileName,
                    generateRiskMapTo(taskId, null, dir, fileName, "岸段风险分布图",
                            splitRows, splitCols, splitTiles)));
        } else {
            List<Map<String, Object>> banks = resolveTaskBanks(taskId, bankIds);
            if (banks.isEmpty()) {
                return Map.of("success", false, "error", "该任务下没有可出图的岸段（无风险结果）");
            }
            if (banks.size() > MAX_BATCH_BANKS) {
                return Map.of("success", false,
                        "error", "单次出图岸段数 " + banks.size() + " 超过上限 " + MAX_BATCH_BANKS);
            }
            for (Map<String, Object> bank : banks) {
                String bankId = String.valueOf(bank.get("bank_id"));
                Object rawName = bank.get("bank_name");
                String bankName = rawName == null || "null".equals(String.valueOf(rawName))
                        ? bankId : String.valueOf(rawName);
                String fileName = "risk_map_" + safeName(bankId) + ".png";
                images.add(imageEntry(bankId, bankName, dirName, fileName,
                        generateRiskMapTo(taskId, bankId, dir, fileName, "岸段风险分布图 · " + bankName,
                                splitRows, splitCols, splitTiles)));
            }
        }

        long ok = images.stream().filter(item -> Boolean.TRUE.equals(item.get("success"))).count();
        int imageTotal = 0;
        int imageSucceeded = 0;
        for (Map<String, Object> item : images) {
            Object tileCount = item.get("tileCount");
            int n = tileCount instanceof Number num ? num.intValue() : 1;
            imageTotal += n;
            if (Boolean.TRUE.equals(item.get("success"))) {
                imageSucceeded += n;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", ok > 0);
        result.put("taskId", taskId);
        result.put("mode", perBank ? "per_bank" : "merged");
        result.put("dir", dirName);
        result.put("total", images.size());
        result.put("succeeded", ok);
        result.put("failed", images.size() - ok);
        result.put("imageTotal", imageTotal);
        result.put("imageSucceeded", imageSucceeded);
        result.put("images", images);
        log.info("[viz] batch done taskId={} total={} ok={} imageTotal={} imageOk={}",
                taskId, images.size(), ok, imageTotal, imageSucceeded);
        return result;
    }

    /**
     * 出图核心：把（可选的）岸段范围的风险断面画成一张图。
     * bankId 为 null 表示不限制岸段（整任务合并）。
     */
    private Map<String, Object> generateRiskMapTo(String taskId, String bankId, String dir,
                                                  String fileName, String title) {
        return generateRiskMapTo(taskId, bankId, dir, fileName, title, null, null, null);
    }

    /**
     * 出图核心（分幅版）：splitRows/splitCols/splitTiles 见
     * {@link #generateRiskMap(String, String, Integer, Integer, String)}。
     */
    private Map<String, Object> generateRiskMapTo(String taskId, String bankId, String dir,
                                                  String fileName, String title,
                                                  Integer splitRows, Integer splitCols,
                                                  String splitTiles) {
        log.info("[viz] generating risk map, taskId={}, bankId={}, file={}", taskId, bankId, fileName);

        // 查询风险数据
        List<Map<String, Object>> riskData = queryRiskData(taskId, bankId);
        if (riskData.isEmpty()) {
            return Map.of("success", false,
                    "error", bankId != null ? ("岸段 " + bankId + " 没有风险数据") : "没有风险数据");
        }
        // 人工标为无效的断面**留在 result set 里但等级为空**（见 queryRiskData 注释），
        // 所以「有行」不等于「有东西可画」：全都被标无效时必须照旧报错，
        // 否则会安静地出一张只剩底图和指北针的空图，报告里也没法解释。
        long invalidCount = riskData.stream()
                .filter(d -> Boolean.TRUE.equals(d.get("manual_invalid")))
                .count();
        long renderableCount = riskData.stream().filter(d -> {
            Object level = d.get("risk_level");
            return level instanceof Number n && n.intValue() >= 1;
        }).count();
        if (renderableCount == 0) {
            String who = bankId != null ? ("岸段 " + bankId) : "该任务";
            String why = invalidCount == riskData.size()
                    ? "的断面已全部标记为无效，按口径不出图"
                    : "没有可着色的风险等级";
            log.info("[viz] skip render: {} {}（断面 {} 条，其中人工无效 {} 条）",
                    who, why, riskData.size(), invalidCount);
            return Map.of("success", false, "error", who + why);
        }
        if (invalidCount > 0) {
            log.info("[viz] 断面 {} 条，其中人工无效 {} 条（等级置空、岸段对应区段不着色）",
                    riskData.size(), invalidCount);
        }

        // 计算风险数据的经纬度范围
        double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        for (Map<String, Object> d : riskData) {
            Object slng = d.get("start_lng"), slat = d.get("start_lat");
            Object elng = d.get("end_lng"), elat = d.get("end_lat");
            if (slng instanceof Number n) { minLng = Math.min(minLng, n.doubleValue()); maxLng = Math.max(maxLng, n.doubleValue()); }
            if (slat instanceof Number n) { minLat = Math.min(minLat, n.doubleValue()); maxLat = Math.max(maxLat, n.doubleValue()); }
            if (elng instanceof Number n) { minLng = Math.min(minLng, n.doubleValue()); maxLng = Math.max(maxLng, n.doubleValue()); }
            if (elat instanceof Number n) { minLat = Math.min(minLat, n.doubleValue()); maxLat = Math.max(maxLat, n.doubleValue()); }
        }
        log.info("[viz] risk data extent: lng=[{}, {}], lat=[{}, {}]", minLng, maxLng, minLat, maxLat);

        // 查询覆盖该范围的 DEM（按岸段出图时范围收窄到该岸段，底图更贴合）
        String demPath = queryDemPathByExtent(minLng, minLat, maxLng, maxLat);

        // 构建请求数据：断面 + 岸段几何（Python 侧按断面风险等级给岸段做沿程颜色插值）
        List<Map<String, Object>> banks = collectBanks(riskData);
        if (banks.isEmpty()) {
            log.warn("[viz] 未归并出任何岸段几何，成图将不含岸段色带"
                    + "（检查 banks.bank_geometry 是否有值、断面行是否带 bank_id）");
        } else {
            log.info("[viz] 归并岸段几何 {} 条，断面 {} 条", banks.size(), riskData.size());
        }

        Map<String, Object> requestData = new LinkedHashMap<>();
        requestData.put("sections", riskData);
        requestData.put("banks", banks);
        requestData.put("dem_path", demPath);
        // 遥感影像底图：画在 DEM/岸段/断面之下的最底层。配置为 null 时载荷里不带 basemap，
        // Python 侧保持原行为（不加底图），三层不受任何影响。key 绝不放进载荷文件。
        Map<String, Object> basemapConfig = buildBasemapConfig();
        if (basemapConfig != null) {
            requestData.put("basemap", basemapConfig);
        }
        // 分幅出图（可选）：rows×cols 均分 + 逐幅自定义边界。
        // 三段均为空时返回 null，载荷里不带 split —— Python 走原单图路径，历史行为不变。
        // 多幅子图由 Python 命名 <name>_r{r}c{c}.png，返回 JSON 含 file_paths/tiles 明细。
        Map<String, Object> splitConfig = null;
        try {
            splitConfig = buildSplitConfig(splitRows, splitCols, splitTiles);
            if (splitConfig != null) {
                requestData.put("split", splitConfig);
                log.info("[viz] 分幅出图 rows={} cols={} customTiles={}",
                        splitConfig.get("rows"), splitConfig.get("cols"),
                        splitConfig.getOrDefault("tiles", "无"));
            }
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }

        // 分幅时一个 Python 进程要渲染 rows×cols 幅（每幅各自取景/取底图瓦片），
        // 超时按子图数等比放大（基准 60s/幅，封顶 600s），避免多幅 + 遥感底图被误杀。
        int tilesPerMap = splitConfig == null ? 1
                : ((Number) splitConfig.get("rows")).intValue()
                * ((Number) splitConfig.get("cols")).intValue();
        int timeoutSeconds = Math.min(600, PYTHON_TIMEOUT_SECONDS * Math.max(1, tilesPerMap));
        return executePython("risk-map", toJson(requestData), title, dir, fileName, timeoutSeconds);
    }

    /**
     * 组装分幅配置（包级可见，供单元测试）。rows/cols 缺省按 1；tilesJson 为
     * JSON 数组字符串。三段均为空 / null 时返回 null —— 表示不分幅，Python 侧
     * 保持单图路径（向后兼容，旧载荷零感知）。
     */
    Map<String, Object> buildSplitConfig(Integer rows, Integer cols, String tilesJson) {
        int r = rows == null ? 1 : rows;
        int c = cols == null ? 1 : cols;
        if (r < 1 || c < 1) {
            throw new IllegalArgumentException("分块数必须 >=1: rows=" + r + ", cols=" + c);
        }
        List<Map<String, Object>> tiles = null;
        if (!isBlank(tilesJson)) {
            try {
                tiles = objectMapper.readValue(tilesJson,
                        new TypeReference<List<Map<String, Object>>>() {});
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(
                        "split_tiles 不是合法 JSON 数组: " + e.getOriginalMessage());
            }
        }
        if (r == 1 && c == 1 && (tiles == null || tiles.isEmpty())) {
            return null;
        }
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("rows", r);
        split.put("cols", c);
        if (tiles != null && !tiles.isEmpty()) {
            split.put("tiles", tiles);
        }
        return split;
    }

    /**
     * 对外暴露的分幅参数校验入口（语义与 {@link #buildSplitConfig} 完全一致）。
     *
     * <p>报告工作流在**起工作流之前**先用它做 fail-fast：分幅参数非法就直接返回
     * 错误，不会白跑完 Step 1 才在 Step 2 炸掉。抽成公开方法是为了让出图与报告
     * 共用同一套校验与报错文案——各写一份必然漂移。
     *
     * @return null 表示不分幅（rows=cols=1 且无自定义 tiles）
     * @throws IllegalArgumentException 分块数 < 1 或 tiles 不是合法 JSON 数组
     */
    public Map<String, Object> validateSplitConfig(Integer rows, Integer cols, String tilesJson) {
        return buildSplitConfig(rows, cols, tilesJson);
    }

    /**
     * 从断面行里归并出涉及的岸段及几何，供 Python 绘制岸段色带。
     *
     * <p>几何取 {@code banks.bank_geometry}（JSONB GeoJSON）——{@code banks.geom} 不被写入，
     * 用它取会得到 NULL，岸段画不出来。
     */
    private List<Map<String, Object>> collectBanks(List<Map<String, Object>> riskData) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : riskData) {
            String bankId = asText(row.get("bank_id"));
            if (bankId == null || byId.containsKey(bankId)) {
                continue;
            }
            Object rawGeometry = row.get("bank_geometry");
            List<List<Double>> coords = extractLineStringCoords(rawGeometry);
            if (coords == null) {
                log.warn("[viz] 岸段 {} 无有效 LineString 几何，跳过岸段绘制"
                                + "（bank_geometry 取回类型={}，为空={}）",
                        bankId,
                        rawGeometry == null ? "null" : rawGeometry.getClass().getSimpleName(),
                        rawGeometry == null || String.valueOf(rawGeometry).isBlank());
                continue;
            }
            Map<String, Object> bank = new LinkedHashMap<>();
            bank.put("bank_id", bankId);
            bank.put("bank_name", asText(row.get("bank_name")));
            bank.put("coordinates", coords);
            byId.put(bankId, bank);
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 把 bank_geometry 解析成 {@code [[lng, lat], ...]}。
     *
     * <p>必须兼容四种输入形态——jsonb 列经 JdbcTemplate 取回时**不是 String**，
     * 而是 {@link PGobject}（同一仓库 AbstractJdbcRepository 已处理过该类型），
     * 只认 String/Map 会把所有岸段静默判为「无有效几何」而全部跳过。
     *
     * <p>可见性放宽为包级，供单元测试直接覆盖各分支。
     */
    @SuppressWarnings("unchecked")
    List<List<Double>> extractLineStringCoords(Object raw) {
        try {
            Object geometry = unwrapJsonbValue(raw);
            if (geometry instanceof String text) {
                if (text.isBlank()) {
                    return null;
                }
                geometry = objectMapper.readValue(text, Map.class);
            }
            if (!(geometry instanceof Map<?, ?> map)) {
                return null;
            }
            // 兼容 GeoJSON Feature 包裹：{"type":"Feature","geometry":{...}}
            Object nested = map.get("geometry");
            Map<?, ?> shape = nested instanceof Map<?, ?> inner ? inner : map;
            if (!"LineString".equals(String.valueOf(shape.get("type")))) {
                return null;
            }
            if (!(shape.get("coordinates") instanceof List<?> points)) {
                return null;
            }
            List<List<Double>> coords = new ArrayList<>();
            for (Object point : points) {
                if (!(point instanceof List<?> pair) || pair.size() < 2) {
                    continue;
                }
                Object lng = pair.get(0);
                Object lat = pair.get(1);
                if (lng instanceof Number x && lat instanceof Number y) {
                    coords.add(List.of(x.doubleValue(), y.doubleValue()));
                }
            }
            return coords.size() >= 2 ? coords : null;
        } catch (Exception e) {
            log.warn("[viz] 解析岸段几何失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把 jsonb 列的取回值归一成「JSON 文本 / 已解析对象 / 其他」。
     * 目前覆盖 PGobject（PostgreSQL jsonb 的标准返回类型）与 byte[] 两种包装。
     */
    private static Object unwrapJsonbValue(Object raw) {
        if (raw instanceof PGobject pgObject) {
            return pgObject.getValue();
        }
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return raw;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "null".equals(text) ? null : text;
    }

    /**
     * 取该任务下真正有风险结果的岸段（可选按 bankIds 过滤）。
     * 只筛出有结果的岸段，避免为无数据的岸段白跑一次出图。
     *
     * <p>HAVING 那一条：断面全部被人工标为无效的岸段不出图。它们留着也只能画出
     * 一张没颜色的岸线（见 {@link #queryRiskData} 的说明），白白占一个「出图失败 /
     * 空图」条目，还会让报告里的图数与岸段数对不上。
     */
    private List<Map<String, Object>> resolveTaskBanks(String taskId, List<String> bankIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT cs.bank_id AS bank_id, b.bank_name AS bank_name " +
                "FROM bank_risk_results r " +
                "JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON cs.bank_id = b.bank_id " +
                "WHERE r.task_id = ? AND r.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(taskId);
        if (bankIds != null && !bankIds.isEmpty()) {
            sql.append(" AND cs.bank_id IN (")
                    .append(String.join(",", Collections.nCopies(bankIds.size(), "?")))
                    .append(")");
            params.addAll(bankIds);
        }
        sql.append(" GROUP BY cs.bank_id, b.bank_name")
                .append(" HAVING count(*) FILTER (WHERE COALESCE(cs.manual_invalid, false) = false) > 0")
                .append(" ORDER BY cs.bank_id");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /**
     * 组装单张图的返回项（含前端可直接使用的静态访问 URL）。
     *
     * <p>分幅出图时 Python 返回 {@code file_paths}（多幅子图的绝对路径，行优先顺序），
     * 此时 entry 额外携带 {@code tileCount} 与 {@code images[]}（每幅的 url/fileName/
     * row/col/bbox/sections_in_tile，信息按 file_path 与 Python 回报的 tiles 明细对齐），
     * 并把首幅 URL 同时写入 {@code url}——只读 url 的旧前端也能正常显示第一幅。
     * 单图（未分幅）时行为与历史版本完全一致。
     */
    Map<String, Object> imageEntry(String bankId, String bankName, String dirName,
                                   String fileName, Map<String, Object> execResult) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("bankId", bankId);
        entry.put("bankName", bankName);
        entry.put("fileName", fileName);
        boolean success = Boolean.TRUE.equals(execResult.get("success"));
        entry.put("success", success);
        if (!success) {
            entry.put("error", execResult.getOrDefault("error", "未知错误"));
            return entry;
        }
        List<String> paths = extractFilePaths(execResult);
        if (paths.size() > 1) {
            Map<String, Map<String, Object>> tileInfoByPath = tileInfoByPath(execResult);
            List<Map<String, Object>> tileImages = new ArrayList<>();
            for (String path : paths) {
                Map<String, Object> img = new LinkedHashMap<>();
                String base = new File(path).getName();
                img.put("fileName", base);
                img.put("url", "/v0/bank/ai/viz-output/" + dirName + "/" + base);
                Map<String, Object> info = tileInfoByPath.get(path);
                if (info != null) {
                    copyIfPresent(info, img, "row", "col", "bbox", "sections_in_tile");
                }
                tileImages.add(img);
            }
            entry.put("tileCount", tileImages.size());
            entry.put("images", tileImages);
            entry.put("url", tileImages.get(0).get("url"));
            return entry;
        }
        // 单图：优先用 Python 回报的实际文件名，缺失时退回请求时的 fileName（历史行为）
        String base = paths.isEmpty() ? fileName : new File(paths.get(0)).getName();
        entry.put("url", "/v0/bank/ai/viz-output/" + dirName + "/" + base);
        return entry;
    }

    /** 从 Python 执行结果里取出全部成图路径：优先 file_paths（分幅），其次 file_path（单图）。 */
    private static List<String> extractFilePaths(Map<String, Object> execResult) {
        Object paths = execResult.get("file_paths");
        if (paths instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        Object single = execResult.get("file_path");
        if (single != null && !String.valueOf(single).isBlank()) {
            return List.of(String.valueOf(single));
        }
        return List.of();
    }

    /** Python 回报的 tiles 明细按 file_path 建索引，供每幅子图附带 row/col/bbox 等信息。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> tileInfoByPath(Map<String, Object> execResult) {
        Object tiles = execResult.get("tiles");
        if (!(tiles instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> byPath = new HashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> tile = (Map<String, Object>) item;
            Object filePath = tile.get("file_path");
            if (filePath != null && !String.valueOf(filePath).isBlank()) {
                byPath.put(String.valueOf(filePath), tile);
            }
        }
        return byPath;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to,
                                      String... keys) {
        for (String key : keys) {
            Object value = from.get(key);
            if (value != null) {
                to.put(key, value);
            }
        }
    }

    /** 收敛成可安全用于文件名/URL 的字符集，顺带挡掉路径穿越。 */
    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 根据经纬度范围查询覆盖的 DEM 文件路径
     */
    private String queryDemPathByExtent(double minLng, double minLat, double maxLng, double maxLat) {
        try {
            // 使用 PostGIS 空间查询，用 geom 列（WGS84）进行范围匹配
            List<Map<String, Object>> tiffs = jdbcTemplate.queryForList(
                    "SELECT tiff_key FROM tiff_bounds " +
                    "WHERE ST_Intersects(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326)) " +
                    "ORDER BY id DESC LIMIT 1",
                    minLng, minLat, maxLng, maxLat
            );
            
            if (!tiffs.isEmpty()) {
                String tiffKey = String.valueOf(tiffs.get(0).get("tiff_key"));
                log.info("[viz] found covering DEM: {}", tiffKey);
                
                String relativePath = tiffKey.replace("tiff/", "resource/tiff/");
                File scriptBase = new File(scriptDir).getAbsoluteFile();
                if (!scriptBase.exists()) {
                    scriptBase = new File(System.getProperty("user.dir"), scriptDir).getAbsoluteFile();
                }
                File demFile = new File(scriptBase, relativePath);
                if (demFile.exists()) {
                    return demFile.getAbsolutePath();
                }
                log.warn("[viz] DEM file not found: {}", demFile.getAbsolutePath());
            }
            
            log.warn("[viz] no DEM found covering extent [{}, {}] - [{}, {}]", minLng, minLat, maxLng, maxLat);
        } catch (Exception e) {
            log.error("[viz] failed to query DEM by extent", e);
        }
        return null;
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
     * 风险取数的 SQL 与绑定参数。
     *
     * <p>单独抽出来只为一件事：让「无效断面必须保留在结果集里、等级置空」这条口径
     * 能被单元测试直接钉住（{@code queryRiskData} 是 private 且依赖 JdbcTemplate，
     * 不抽就只能靠实跑一张图去肉眼看）。
     */
    record RiskDataQuery(String sql, List<Object> params) {}

    /** 组装风险取数查询（包级可见，供单元测试锁口径）。 */
    RiskDataQuery buildRiskDataQuery(String taskId, String bankId) {
        // 注意：banks.geom 不被写入（BankMapper 只维护 bank_geometry jsonb），
        // 所以岸段几何取 b.bank_geometry，与前端 ResultPage 的口径保持一致。
        // ::text 是刻意的：jsonb 列经 JdbcTemplate 取回是 PGobject，显式转 text 让驱动
        // 直接返回字符串，避免依赖驱动的类型行为（Java 侧仍兼容 PGobject 作为兜底）。
        // bank_id 用 COALESCE 兜底：cross_sections 缺行时 cs.bank_id 为 NULL，
        // 而 bank_risk_results 自带 bank_id 且实测 100% 非空。
        //
        // ⚠ 人工标记无效的断面**必须留在结果集里**，只把 risk_level 置空（见下方 CASE），
        //   不能像 2026-10-26 那版那样直接 `AND manual_invalid = false` 把它们过滤掉。
        //   原因：岸段色带是按「断面管辖区间」着色的（Python 侧
        //   gradient.build_span_colors 用最近断面给每个折线段定色）。断面行一旦缺席，
        //   它原本管辖的那段岸线就改由**左右两个有效断面**就近接管 —— 于是被标为无效
        //   的那一段照样被邻居的颜色铺满，图上根本看不出「这里被隐藏了」。
        //   保留行 + 等级为空，Python 的 is_rendered_level(None)=False 就会让整段不画，
        //   与页面「灰虚线、不着色」是同一套口径。
        //   COALESCE 兜住断面已删（cs 缺失）的情况。
        StringBuilder sql = new StringBuilder(
                "SELECT r.section_id, cs.section_name, COALESCE(cs.bank_id, r.bank_id) as bank_id, " +
                "cs.distance, " +
                "CASE WHEN COALESCE(cs.manual_invalid, false) THEN NULL ELSE r.risk_level END as risk_level, " +
                "COALESCE(cs.manual_invalid, false) as manual_invalid, " +
                "r.indicators->>'result' as risk_value, " +
                "b.bank_name, b.bank_geometry::text as bank_geometry, " +
                "ST_X(ST_StartPoint(cs.geom)) as start_lng, " +
                "ST_Y(ST_StartPoint(cs.geom)) as start_lat, " +
                "ST_X(ST_EndPoint(cs.geom)) as end_lng, " +
                "ST_Y(ST_EndPoint(cs.geom)) as end_lat " +
                "FROM bank_risk_results r " +
                "LEFT JOIN cross_sections cs ON r.section_id = cs.section_id " +
                "LEFT JOIN banks b ON COALESCE(cs.bank_id, r.bank_id) = b.bank_id " +
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

        return new RiskDataQuery(sql.toString(), params);
    }

    /**
     * 查询风险数据（含人工无效断面，其 risk_level 为空，见 buildRiskDataQuery）。
     */
    private List<Map<String, Object>> queryRiskData(String taskId, String bankId) {
        RiskDataQuery query = buildRiskDataQuery(taskId, bankId);
        return jdbcTemplate.queryForList(query.sql(), query.params().toArray());
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

    // ------------------------------------------------------------------
    // section_profiles.profile_data 的读取口径
    //
    // 写入端 SectionProfileService#buildProfileData 落盘的结构是
    //   {"profile":[{"index":0,"distance":0.0,"x":..,"y":..,"elevation":..}, ...],
    //    "interval":20.0,"deepest_index":42,"slope_foot_index":38,
    //    "points_v":[...],"Sa_v":[...]}
    // 而这里历史上读的是键 "points" + List<List<Double>>（模型原始 points 的形态），
    // 键名与元素形态双双不匹配 ⇒ 取到 null ⇒ gridData / sections 恒为空 ⇒
    // /viz/heatmap、/viz/section 永远回「无法构建网格数据 / 断面数据」，
    // AI 报告的 generate_scour_heatmap、generate_section_comparison_chart 也拿不到图。
    //
    // 注意：前端结果页的「断面剖面折线」走的是**另一条通道**
    // （GET /v0/bank/tasks/{taskId}/section-profiles 直接透传 profile_data.profile，
    //   ResultPage 读 profile_data.profile），它一直是好的。别因为这条坏了就误判前端也坏。
    // ------------------------------------------------------------------

    /** 断面上的一个采样点：沿断面的累计距离（m）与高程（m）。 */
    record ProfileSample(double distance, double elevation) {
    }

    /**
     * 取 {@code profile_data} 并解析成 Map。
     *
     * <p>兼容 jsonb 经 JdbcTemplate 取回时的 {@link PGobject} 形态（与岸段几何同一个坑：
     * 直接 {@code toString()} 能拿到 JSON，但类型判断一旦写成 {@code instanceof String}
     * 就会把所有行静默丢弃）。解析失败返回 {@code null}，由调用方跳过单条，不整体失败。
     */
    Map<String, Object> readProfileData(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return asObjectMap(map);
        }
        String json = value instanceof PGobject pg ? pg.getValue() : value.toString();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            // 单条脏数据只跳过这一条，不要把整批出图拖垮；不打完整栈，避免批量任务刷屏。
            log.warn("[viz] parse profile data failed, section skipped: {}", e.getOriginalMessage());
            return null;
        }
    }

    /**
     * 从 {@code profile_data} 提取 (距离, 高程) 序列。
     *
     * <p>两种形态都认，避免老任务回放时再次失效：
     * <ul>
     *   <li>现行 {@code profile}：元素是 dict，{@code distance} 缺失时用
     *       {@code interval × index} 回退；</li>
     *   <li>早期 {@code points}：元素是 {@code [x, y, elevation]}，没有 distance，
     *       只能按 {@code interval × 下标} 反推。</li>
     * </ul>
     * 高程缺失 / 非有限值的点直接丢弃（与模型侧挡住 nodata 的口径一致）。
     */
    static List<ProfileSample> extractProfileSamples(Map<String, Object> profileData) {
        List<ProfileSample> samples = new ArrayList<>();
        if (profileData == null) {
            return samples;
        }
        double interval = numberOrZero(profileData.get("interval"));

        if (profileData.get("profile") instanceof List<?> rows && !rows.isEmpty()) {
            int position = 0;
            for (Object rowValue : rows) {
                if (rowValue instanceof Map<?, ?> row) {
                    Double elevation = numberOrNull(row.get("elevation"));
                    if (elevation != null) {
                        Double distance = numberOrNull(row.get("distance"));
                        if (distance == null) {
                            Double index = numberOrNull(row.get("index"));
                            distance = interval * (index == null ? position : index);
                        }
                        samples.add(new ProfileSample(distance, elevation));
                    }
                }
                position++;
            }
            if (!samples.isEmpty()) {
                return samples;
            }
        }

        if (profileData.get("points") instanceof List<?> points) {
            for (int index = 0; index < points.size(); index++) {
                Object pointValue = points.get(index);
                if (!(pointValue instanceof List<?> point) || point.size() < 3) {
                    continue;
                }
                Double elevation = numberOrNull(point.get(2));
                if (elevation != null) {
                    samples.add(new ProfileSample(interval * index, elevation));
                }
            }
        }
        return samples;
    }

    /**
     * 把各条断面的高程序列重采样到统一长度。
     *
     * <p>热力图脚本对入参直接做 {@code np.array(grid_data)}，numpy 2.x 遇到不等长会抛
     * {@code ValueError: inhomogeneous shape}，且这一步在 {@code data.size == 0} 判断之前，
     * 异常会直接落进脚本失败分支。断面经过「只保留最长连续有效段」的裁剪后点数不再恒为 100
     * （实测 13~100 点），所以必须在这里拉齐。重采样按断面自身的归一化位置线性插值，
     * 与 heatmap.py 的 {@code generate_scour_heatmap_from_profiles} 同一口径。
     */
    static List<List<Double>> resampleToUniformLength(List<List<Double>> series) {
        int target = 0;
        for (List<Double> row : series) {
            target = Math.max(target, row.size());
        }
        if (target == 0) {
            return series;
        }
        List<List<Double>> uniform = new ArrayList<>(series.size());
        for (List<Double> row : series) {
            if (row.size() == target) {
                uniform.add(new ArrayList<>(row));
                continue;
            }
            List<Double> resampled = new ArrayList<>(target);
            int last = row.size() - 1;
            for (int i = 0; i < target; i++) {
                if (last <= 0) {
                    resampled.add(row.get(0));
                    continue;
                }
                double position = (double) i * last / (target - 1);
                int low = (int) Math.floor(position);
                int high = Math.min(low + 1, last);
                double ratio = position - low;
                resampled.add(row.get(low) + (row.get(high) - row.get(low)) * ratio);
            }
            uniform.add(resampled);
        }
        return uniform;
    }

    /**
     * 把「断面 ID 或断面名」解析成真正的 {@code section_id}（限定在同一任务内）。
     *
     * <p>存在的理由：报告 Step 5 历史上把**断面名**当 ID 传给 {@link #generateHeatmap}，
     * 而 {@link #queryProfiles} 过滤的是 {@code section_id} —— 两者在库里是两套值
     * （「断面6」vs {@code sec-task-...-5}，实测 867 行零重合），于是每条断面都回
     * 「没有剖面数据」，报告里两张断面图永远缺图。
     *
     * <p>解析顺序：先认 {@code section_id} 精确命中，再退化为 {@code section_name}。
     * 断面名在任务内**可能重名**（实测有「新建断面1」一条任务里出现 4 次），
     * 因此按「有没有剖面」优先、再按 {@code section_id} 排序取第一条，保证结果确定；
     * 真正需要精确匹配的调用方应当直接传 ID（Step 1 的文本已带回）。
     *
     * @return 解析到的 {@code section_id}；任务或入参为空、或库里查不到时返回 {@code null}
     */
    public String resolveSectionId(String taskId, String sectionIdOrName) {
        if (taskId == null || taskId.isBlank() || sectionIdOrName == null || sectionIdOrName.isBlank()) {
            return null;
        }
        String key = sectionIdOrName.trim();
        List<String> bySection = jdbcTemplate.queryForList(
                "SELECT cs.section_id FROM cross_sections cs "
                        + "WHERE cs.task_id = ? AND cs.deleted_at IS NULL "
                        + "  AND (cs.section_id = ? OR cs.section_name = ?) "
                        + "ORDER BY CASE WHEN cs.section_id = ? THEN 0 ELSE 1 END, "
                        + "         CASE WHEN EXISTS (SELECT 1 FROM section_profiles p "
                        + "              WHERE p.section_id = cs.section_id AND p.deleted_at IS NULL) "
                        + "              THEN 0 ELSE 1 END, "
                        + "         cs.section_id "
                        + "LIMIT 1",
                String.class, taskId, key, key, key);
        if (!bySection.isEmpty()) {
            return bySection.get(0);
        }
        // cross_sections 里没有（断面被改过/删过），退回断面档案表碰一下
        List<String> byProfile = jdbcTemplate.queryForList(
                "SELECT section_id FROM section_profiles "
                        + "WHERE task_id = ? AND deleted_at IS NULL "
                        + "  AND (section_id = ? OR section_name = ?) "
                        + "ORDER BY CASE WHEN section_id = ? THEN 0 ELSE 1 END, section_id "
                        + "LIMIT 1",
                String.class, taskId, key, key, key);
        return byProfile.isEmpty() ? null : byProfile.get(0);
    }

    /**
     * 构建网格数据（每条断面一行高程，已拉齐到统一长度）
     */
    private List<List<Double>> buildGridData(List<Map<String, Object>> profiles) {
        List<List<Double>> elevations = new ArrayList<>();
        for (Map<String, Object> row : profiles) {
            List<ProfileSample> samples = extractProfileSamples(readProfileData(row.get("profile_data")));
            if (samples.isEmpty()) {
                continue;
            }
            List<Double> elevation = new ArrayList<>(samples.size());
            for (ProfileSample sample : samples) {
                elevation.add(sample.elevation());
            }
            elevations.add(elevation);
        }
        return resampleToUniformLength(elevations);
    }

    /**
     * 构建断面数据（横轴取沿断面的累计距离，纵轴取高程）
     */
    private List<Map<String, Object>> buildSectionData(List<Map<String, Object>> profiles) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Map<String, Object> row : profiles) {
            List<ProfileSample> samples = extractProfileSamples(readProfileData(row.get("profile_data")));
            if (samples.isEmpty()) {
                continue;
            }
            List<Double> xCoords = new ArrayList<>(samples.size());
            List<Double> elevation = new ArrayList<>(samples.size());
            for (ProfileSample sample : samples) {
                xCoords.add(sample.distance());
                elevation.add(sample.elevation());
            }

            Map<String, Object> section = new LinkedHashMap<>();
            section.put("section_name", row.get("section_name"));
            section.put("x_coords", xCoords);
            section.put("elevation", elevation);
            section.put("deepest_index", row.get("deepest_index"));
            section.put("slope_foot_index", row.get("slope_foot_index"));
            section.put("timepoint", row.get("timepoint"));
            sections.add(section);
        }
        return sections;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 把任意 JSON 数值转成 double；非数值 / null / NaN / Infinity 一律返回 null。 */
    static Double numberOrNull(Object value) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return Double.isFinite(parsed) ? parsed : null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                double parsed = Double.parseDouble(trimmed);
                return Double.isFinite(parsed) ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double numberOrZero(Object value) {
        Double parsed = numberOrNull(value);
        return parsed == null ? 0d : parsed;
    }

    /**
     * 执行 Python 脚本（沿用任务级输出目录，文件名交给 Python 默认值）
     */
    private Map<String, Object> executePython(String command, String dataJson, String title) {
        return executePython(command, dataJson, title, resolveOutputDir(), null);
    }

    /**
     * 构建下发给 Python 的底图配置；返回 null 表示本次出图不带底图。
     *
     * <p>三种开关状态：
     * <ul>
     *   <li>{@code false/0/off} —— 显式关闭，返回 null；</li>
     *   <li>{@code true/1/on} —— 强制启用：哪怕没有 key 也下发（Python 侧会打印
     *       未启用原因并继续出图，不会失败）；</li>
     *   <li>{@code auto}（缺省）—— 有 key（或 provider=local 不需要 key）才下发，
     *       没有 key 时只提示一次「如何启用」，不刷屏。</li>
     * </ul>
     *
     * <p><b>安全约定</b>：天地图 key 绝不写进载荷 JSON —— 载荷落盘为
     * {@code temp_data_*.json}，位于对外可访问的输出目录。key 由
     * {@link #executePython} 通过子进程环境变量 {@code TIANDITU_TK} 传递。
     */
    Map<String, Object> buildBasemapConfig() {
        // 字段可能未被注入（单元测试 / 配置缺失），此时按缺省 auto 处理；
        // 不能用 String.valueOf(null)——它会得到字面量 "null" 而不是走 auto 分支
        String rawEnabled = isBlank(basemapEnabled) ? "auto" : basemapEnabled.trim().toLowerCase();
        switch (rawEnabled) {
            case "false", "0", "off", "no" -> {
                return null;
            }
            default -> { /* true / on / auto：继续构建 */ }
        }

        String provider = basemapProvider == null ? "tianditu" : basemapProvider.trim().toLowerCase();
        if (provider.isEmpty()) {
            provider = "tianditu";
        }

        boolean needsKey = "tianditu".equals(provider);
        boolean hasKey = !isBlank(basemapKey);
        boolean isAuto = "auto".equals(rawEnabled);

        if (isAuto && needsKey && !hasKey) {
            // auto 且没有 key：不污染载荷，提示一次怎么开（批量出图时不刷屏）
            if (!basemapNoticeLogged) {
                basemapNoticeLogged = true;
                log.info("[viz] 遥感底图未启用（auto 且未配置 key）。"
                        + "到天地图开放平台申请 tk 后，经环境变量 TIANDITU_TK 或配置项 "
                        + "app.ai.visualization.basemap.key 提供即可自动启用；"
                        + "关闭本提示可设 app.ai.visualization.basemap.enabled=false");
            }
            return null;
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("provider", provider);
        if (basemapLayer != null && !basemapLayer.isBlank()) {
            config.put("layer", basemapLayer.trim().toLowerCase());
        }
        if (basemapZoom > 0) {
            config.put("zoom", basemapZoom);
        }
        // alpha 是 double 原始类型：未注入时是 0.0（单元测试/配置缺失），生产缺省是 1.0。
        // 两个值都不下发：0 会把底图画成全透明（等于没画），1.0 是 Python 侧缺省。
        // 真想调淡底图时配置 (0,1) 开区间内的值。
        if (basemapAlpha > 0 && basemapAlpha < 1) {
            config.put("alpha", basemapAlpha);
        }
        log.info("[viz] 遥感底图配置已下发 provider={} layer={} zoom={} alpha={}（key 经环境变量传递，不进载荷）",
                provider, config.getOrDefault("layer", "img"), config.getOrDefault("zoom", "自适应"),
                config.getOrDefault("alpha", 1.0));
        return config;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 执行 Python 出图脚本。
     *
     * @param dir      输出目录；为 null 时退回任务级目录
     * @param fileName 输出文件名；为 null 时用 Python 默认名（固定名，同目录多次调用会互相覆盖，
     *                 批量出图必须显式指定）
     */
    private Map<String, Object> executePython(String command, String dataJson, String title,
                                              String dir, String fileName) {
        return executePython(command, dataJson, title, dir, fileName, PYTHON_TIMEOUT_SECONDS);
    }

    private Map<String, Object> executePython(String command, String dataJson, String title,
                                              String dir, String fileName, int timeoutSeconds) {
        try {
            if (dir == null) {
                dir = resolveOutputDir();
            }
            new File(dir).mkdirs();
            // 临时数据文件按输出名隔离，避免同一目录内多次调用互相覆盖
            String dataFile = dir + "/temp_data_"
                    + (fileName != null ? safeName(fileName) : "default") + ".json";
            try (FileWriter writer = new FileWriter(dataFile)) {
                writer.write(dataJson);
            }

            // 获取 bank-model-server 的绝对路径
            File scriptBase = new File(scriptDir).getAbsoluteFile();
            if (!scriptBase.exists()) {
                scriptBase = new File(System.getProperty("user.dir"), scriptDir).getAbsoluteFile();
            }
            
            // 构建命令 - 使用 uv run
            List<String> cmd = new ArrayList<>(List.of(
                    "uv", "run", "python", "-m", "visualization.main",
                    command,
                    "--data", new File(dataFile).getAbsolutePath(),
                    "--title", title,
                    "--output", new File(dir).getAbsolutePath()
            ));
            if (fileName != null) {
                cmd.add("--name");
                cmd.add(fileName);
            }

            log.info("[viz] executing: {}", String.join(" ", cmd));
            log.info("[viz] working dir: {}", scriptBase.getAbsolutePath());
            // 执行前审计：bank-model-server 为本地固定可信模块，仅记录不扫描
            log.info("[viz] execute-python-audit cmd={} workdir={}", String.join(" ", cmd), scriptBase.getAbsolutePath());

            // 执行命令 - 设置工作目录为 bank-model-server
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(scriptBase);
            pb.redirectErrorStream(true);
            // 天地图 key 只经子进程环境变量传递（Python 侧 basemap 模块从 TIANDITU_TK 读取），
            // 绝不写进 temp_data_*.json 载荷文件——它落在对外可访问的输出目录里。
            if (!isBlank(basemapKey)) {
                pb.environment().put("TIANDITU_TK", basemapKey);
            }

            Process process = pb.start();
            String output = readProcessOutput(process);
            boolean completed = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);

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
