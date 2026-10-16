package com.yangtze.bankwarning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangtze.bankwarning.config.ModelServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流场（水动力流场）取数：把模型服务里已生成的渲染产物转发给前端。
 *
 * <p>背景：流场这套东西**只有生成端，从来没有消费端** —— 生成器是模型服务里的 C++
 * {@code model/flowFieldTextureBuilder}，产物落在
 * {@code resource/hydrodynamic/<域>/<年>/<集合>/<工况>/renderResource/}。
 * 前端要展示，就得有一条取数通道，本类就是那条通道。
 *
 * <p><b>两类产物，两种 id</b>（这是本类最容易看混的地方）：
 * <table border="1">
 *   <tr><th></th><th>内置工况</th><th>按场景生成</th></tr>
 *   <tr><td>id 形态</td><td>{@code 84000dc}（流量档 + 潮型）</td><td>{@code f-<12 位十六进制>}</td></tr>
 *   <tr><td>磁盘前缀</td><td>{@link #RESOURCE_PREFIX}</td><td>{@link #SCENE_PREFIX}</td></tr>
 *   <tr><td>范围</td><td>2024-08 一次性算的固定素材，只盖住大区 DEM 的约 8%</td>
 *       <td>由 {@code model/flowFieldSceneResource.py} 按 DEM 范围从库里点云生成，覆盖整片 DEM</td></tr>
 *   <tr><td>来源</td><td>模型服务资源树（{@code /hydrodynamic/list}）</td>
 *       <td>模型服务场景表（{@code /flow-field/scenes} 与 {@code /flow-field/resolve}）</td></tr>
 * </table>
 * 两类共用同一组路径（{@code /{id}/description|velocity/{frame}|mask}），id 形态决定去哪里取，
 * 前端因此不需要为场景多写一套请求。
 *
 * <p>⚠️ 两个必须记住的坑（都实测过）：
 * <ol>
 *   <li>内置工况的产物 json 内部把路径写成 {@code hydrodynamic/Mzs/2023/standard/...}，
 *       但磁盘与模型服务可服务的实际路径是 {@code hydrodynamic/Mzs/default/standard/...}
 *       —— 照抄 json 里的路径会 404。所以这里拼路径一律用 {@link #RESOURCE_PREFIX}。
 *       （按场景生成的产物走的是生成时传进去的 {@code resource_url}，本来就是对的，改写它是空操作。）</li>
 *   <li>{@code name} 是**路径状**查询参数，必须白名单校验，否则就是一条现成的路径穿越通道。</li>
 * </ol>
 */
@Service
public class FlowFieldService {

    private static final Logger log = LoggerFactory.getLogger(FlowFieldService.class);

    /** 流场渲染产物根前缀：内置工况固定这个前缀（见类注释坑 1）。 */
    private static final String RESOURCE_PREFIX = "hydrodynamic/Mzs/default/standard/";
    /** 按场景生成的产物根前缀。id 自带一层目录，所以前缀就是 {@code flowField/}。 */
    private static final String SCENE_PREFIX = "flowField/";
    /** 产物子目录，固定值。 */
    private static final String RENDER_DIR = "/renderResource/";

    /** 描述文件里那些"装了一串资源路径"的字段，需要统一改写前缀（见 {@link #LEGACY_SET_SEGMENT}）。 */
    private static final List<String> PATH_LIST_KEYS = List.of("area_masks", "flow_fields", "height_maps", "projection");
    /** 内置工况的描述文件内部写的集合段（年份），实际可服务路径用的是 default —— 照抄必 404。 */
    private static final String LEGACY_SET_SEGMENT = "/2023/";

    /** 工况 id = 流量档(数字) + 潮型(dc/xc/zc)，如 84000dc。这条正则同时是防路径穿越的白名单。 */
    private static final Pattern CASE_ID_PATTERN = Pattern.compile("^(\\d{4,6})(dc|xc|zc)$");
    /**
     * 场景 id = {@code f-} + 12 位十六进制，如 {@code f-fab276146b9c}。
     * 它由「DEM 经纬度范围 + 流量档 + 潮位 + 分辨率」哈希而来，规则只在
     * {@code model/flowFieldSceneResource.py::scene_id_for()} 里实现一次 ——
     * **Java 侧不要复刻这个哈希**，要拿场景就调 {@code /flow-field/resolve}。
     */
    private static final Pattern SCENE_ID_PATTERN = Pattern.compile("^f-[0-9a-f]{12}$");
    /** 单帧纹理的帧号上限（产物是 uv_0..uv_25，共 26 帧）。 */
    private static final int MAX_FRAME = 25;
    private static final int FRAME_COUNT = MAX_FRAME + 1;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    /** 给 Jackson 用的类型令牌（{@link ParameterizedTypeReference} 只认给 RestClient，不能给 ObjectMapper）。 */
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {
    };

    private final RestClient modelRestClient;
    private final ModelServiceProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FlowFieldService(RestClient modelRestClient, ModelServiceProperties properties) {
        this.modelRestClient = modelRestClient;
        this.properties = properties;
    }

    /**
     * 列出可用的流场工况。
     *
     * @return 每项含 {@code case_id}(如 84000dc) / {@code water_qs}(流量档) / {@code tidal_level}(潮型) / {@code frames}
     */
    public List<Map<String, Object>> listCases() {
        Map<String, Object> payload = fetchJson("/v0/fs/resource/hydrodynamic/list");
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object domain : asList(payload.get("resource"))) {
            Map<String, Object> domainMap = asMap(domain);
            String domainName = str(domainMap.get("name"));
            for (Object date : asList(domainMap.get("date"))) {
                Map<String, Object> dateMap = asMap(date);
                String year = str(dateMap.get("year"));
                for (Object set : asList(dateMap.get("sets"))) {
                    Map<String, Object> setMap = asMap(set);
                    String setName = str(setMap.get("name"));
                    for (Object item : asList(setMap.get("list"))) {
                        Map<String, Object> itemMap = asMap(item);
                        // temp=true 的目录是临时产物，不对外暴露
                        if (Boolean.TRUE.equals(itemMap.get("temp"))) {
                            continue;
                        }
                        String caseId = str(itemMap.get("name"));
                        Matcher matcher = CASE_ID_PATTERN.matcher(caseId);
                        if (!matcher.matches()) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("case_id", caseId);
                        row.put("water_qs", matcher.group(1));
                        row.put("tidal_level", matcher.group(2));
                        row.put("frames", FRAME_COUNT);
                        row.put("dataset_domain", domainName);
                        row.put("year", year);
                        row.put("set_name", setName);
                        cases.add(row);
                    }
                }
            }
        }
        // 按流量档数值、再按潮型排序，前端下拉才稳定
        cases.sort((a, b) -> {
            int byQs = Integer.compare(Integer.parseInt(str(a.get("water_qs"))),
                    Integer.parseInt(str(b.get("water_qs"))));
            return byQs != 0 ? byQs : str(a.get("tidal_level")).compareTo(str(b.get("tidal_level")));
        });
        log.info("[flow-field] cases listed, count={}", cases.size());
        return cases;
    }

    /**
     * 列出按场景生成的流场（覆盖整片 DEM 的那些）。
     *
     * <p>每个场景带 {@code scene_id / tiff_key / tiff_keys / water_qs / tidal_level / grid /
     * extent / flow_boundary / coverage / generated_at}，够前端直接渲染下拉与说明文字，
     * 不必再逐条请求 description。
     *
     * <p>只有已经生成好的场景才会出现 —— 生成一次 60 s 以上、产物约 190 MB，
     * 绝不会在请求里现做（见 {@code model/flowFieldSceneResource.py::resolve_scene}）。
     */
    public List<Map<String, Object>> listScenes() {
        Map<String, Object> payload = fetchJson("/v0/fs/resource/flow-field/scenes");
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (Object item : asList(payload.get("scenes"))) {
            Map<String, Object> manifest = asMap(item);
            String sceneId = str(manifest.get("scene_id"));
            // 与工况表同一套思路：只放行形态合法的 id，它们同时就是防路径穿越的白名单
            if (!SCENE_ID_PATTERN.matcher(sceneId).matches()) {
                continue;
            }
            if (Boolean.TRUE.equals(manifest.get("temp"))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scene_id", sceneId);
            row.put("tiff_key", str(manifest.get("tiff_key")));
            row.put("tiff_keys", asList(manifest.get("tiff_keys")));
            row.put("anchor", str(manifest.get("anchor")));
            row.put("water_qs", str(manifest.get("water_qs")));
            row.put("tidal_level", str(manifest.get("tidal_level")));
            row.put("resolution", manifest.get("resolution"));
            row.put("frames", manifest.get("frames"));
            row.put("grid", manifest.get("grid"));
            row.put("extent", manifest.get("extent"));
            row.put("flow_boundary", manifest.get("flow_boundary"));
            row.put("texture_size", manifest.get("texture_size"));
            row.put("coverage", manifest.get("coverage"));
            row.put("dem_bounds", manifest.get("dem_bounds"));
            row.put("point_count", manifest.get("point_count"));
            row.put("generated_at", manifest.get("generated_at"));
            scenes.add(row);
        }
        scenes.sort((a, b) -> str(a.get("scene_id")).compareTo(str(b.get("scene_id"))));
        log.info("[flow-field] scenes listed, count={}", scenes.size());
        return scenes;
    }

    /**
     * 按「任务所属的 DEM + 水情」解析出场景 id。
     *
     * <p>场景 id 是哈希，Java 侧算不出来（也不该复刻规则），所以解析这件事交给模型服务；
     * 本方法只是把 {@code tiff_key} 换成 {@code scene_id} 的翻译层。
     *
     * @return {@code {found: bool, scene_id: string, scene?: {...}}}；
     *         {@code found=false} 表示这片 DEM 的这个水情档还没生成过
     */
    public Map<String, Object> resolveScene(String tiffKey, String waterQs, String tidalLevel, Integer resolution) {
        if (tiffKey == null || tiffKey.isBlank() || waterQs == null || waterQs.isBlank()
                || tidalLevel == null || tidalLevel.isBlank()) {
            throw new IllegalArgumentException("解析场景需要 tiff_key、water_qs、tidal_level 三个参数");
        }
        int res = resolution == null || resolution <= 0 ? 1024 : resolution;
        return fetchJson(builder -> builder
                .path("/v0/fs/resource/flow-field/resolve")
                .queryParam("tiff_key", tiffKey)
                .queryParam("water_qs", waterQs)
                .queryParam("tidal_level", tidalLevel)
                .queryParam("resolution", res)
                .build());
    }

    /**
     * 取某个工况**或场景**的流场描述文件。
     *
     * <p>用它拿 {@code extent}（**归一化 Web 墨卡托**包围盒，注意 y 轴：纬度越高数值越小）、
     * {@code flow_boundary}（u/v 的反归一化基准）、{@code texture_size} 与逐帧路径。
     * 前端拿 {@code extent} 铺粒子并 {@code fitBounds}，所以它必须真的是那片 DEM 的范围 ——
     * 内置工况给的是 68 km 小范围，按场景生成的给的是整片 DEM。
     *
     * <p>返回体原样透传，只有一处例外：**内置工况**的描述文件里资源路径写成
     * {@code hydrodynamic/Mzs/2023/...}，而实际可服务路径是 {@code hydrodynamic/Mzs/default/...}
     * （类注释坑 1）。这里把前缀改写掉，免得前端照着拼请求撞 404。
     * 按场景生成的产物写的是生成时传进去的 {@code flowField/<id>/...}，本来就对，
     * 这一替换对它是个空操作。
     */
    public Map<String, Object> loadDescription(String caseId) {
        Map<String, Object> description = fetchJsonObject(resourcePath(caseId, "flow_field_description.json"));
        for (String key : PATH_LIST_KEYS) {
            Object value = description.get(key);
            if (value instanceof List<?> list) {
                List<Object> fixed = new ArrayList<>(list.size());
                for (Object item : list) {
                    fixed.add(item instanceof String path ? path.replace(LEGACY_SET_SEGMENT, "/default/") : item);
                }
                description.put(key, fixed);
            }
        }
        return description;
    }

    /** 取第 frame 帧的流速纹理（{@code uv_N.png}）。 */
    public byte[] loadVelocityFrame(String caseId, int frame) {
        if (frame < 0 || frame > MAX_FRAME) {
            throw new IllegalArgumentException("帧号超出范围：" + frame + "（可用 0-" + MAX_FRAME + "）");
        }
        return fetchBytes(resourcePath(caseId, "uv_" + frame + ".png"));
    }

    /**
     * 取水域掩膜（{@code mask_0.png}）。
     *
     * <p>只有第 0 帧这一张，26 帧共用：每个像素存一对 16 位大端索引 (ix, iy)，
     * 水域格存自己的坐标、陆域格存一个随机水域格坐标 —— 也就是"粒子飘到岸上就重生"的索引图。
     */
    public byte[] loadAreaMask(String caseId) {
        return fetchBytes(resourcePath(caseId, "mask_0.png"));
    }

    /** 可用帧数（产物固定 26 帧）。 */
    public int frameCount() {
        return FRAME_COUNT;
    }

    // ==================== 内部 ====================

    /**
     * 由 id 形态决定资源前缀 —— 这一步**同时就是白名单校验**。
     *
     * <p>两条链路共用同一组 URL，所以"校验"和"选前缀"必须在同一个地方做完；
     * 分开写迟早会出现"某个入口校验了、另一个没校验"的路径穿越口子。
     * 包级可见是为了能被单测直接覆盖：id 会原样拼进请求路径，漏校验就是一条现成的穿越通道，
     * 这种地方值得有单独断言，而不是靠"读代码看着对"。
     *
     * @throws IllegalArgumentException id 形态不合法（含路径分隔符、`..`、大小写不符等）
     */
    static String resourcePrefixFor(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("流场资源 id 不能为空");
        }
        if (SCENE_ID_PATTERN.matcher(id).matches()) {
            return SCENE_PREFIX;
        }
        if (CASE_ID_PATTERN.matcher(id).matches()) {
            return RESOURCE_PREFIX;
        }
        throw new IllegalArgumentException("非法的流场工况/场景 id：" + id);
    }

    private String resourcePath(String id, String fileName) {
        return resourcePrefixFor(id) + id + RENDER_DIR + fileName;
    }

    /** 读模型服务的 json 接口（资源清单、场景清单、场景解析）。 */
    private Map<String, Object> fetchJson(String path) {
        return fetchJson(builder -> builder.path(path).build());
    }

    private Map<String, Object> fetchJson(Function<UriBuilder, URI> uriFunction) {
        try {
            Map<String, Object> body = modelRestClient.get()
                    .uri(uriFunction)
                    .retrieve()
                    .body(MAP_TYPE);
            return body == null ? Map.of() : body;
        } catch (RuntimeException exception) {
            log.error("[flow-field] json fetch failed: {}", exception.getMessage());
            throw new IllegalStateException("流场资源读取失败，请确认模型服务（"
                    + properties.getBaseUrl() + "）是否已启动", exception);
        }
    }

    /**
     * 取描述文件并解析成 Map。
     *
     * <p>⚠️ 这里**不能**直接用 {@code .body(MAP_TYPE)}：模型服务对 {@code .json} 文件返回的
     * Content-Type 是 {@code application/octet-stream}（实测），Jackson 转换器不支持该媒体类型，
     * 会抛 {@code Could not extract response} —— 表面现象就是描述接口恒 404。改成先取
     * {@code String}（StringHttpMessageConverter 接受任意媒体类型）再自己解析。
     */
    private Map<String, Object> fetchJsonObject(String name) {
        String text;
        try {
            text = modelRestClient.get()
                    .uri(builder -> builder.path("/v0/fs/resource/file").queryParam("name", name).build())
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException exception) {
            log.error("[flow-field] description download failed, name={}, err={}", name, exception.getMessage());
            throw new IllegalArgumentException("流场描述文件不存在或不可读：" + name);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("流场描述文件为空：" + name);
        }
        try {
            return objectMapper.readValue(text, MAP_REF);
        } catch (Exception exception) {
            log.error("[flow-field] description parse failed, name={}, err={}", name, exception.getMessage());
            throw new IllegalStateException("流场描述文件解析失败：" + name, exception);
        }
    }

    private byte[] fetchBytes(String name) {
        try {
            byte[] body = modelRestClient.get()
                    .uri(builder -> builder.path("/v0/fs/resource/file").queryParam("name", name).build())
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new IllegalArgumentException("流场资源为空：" + name);
            }
            return body;
        } catch (RuntimeException exception) {
            log.error("[flow-field] resource fetch failed, name={}, err={}", name, exception.getMessage());
            throw new IllegalArgumentException("流场资源不存在或不可读：" + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
