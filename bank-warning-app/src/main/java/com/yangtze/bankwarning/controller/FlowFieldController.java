package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.FlowFieldService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流场取数接口。
 *
 * <p>只做「转发 + 白名单校验」，不做任何几何或数值处理：产物是模型服务里
 * {@code flowFieldTextureBuilder} 生成的现成纹理，语义见 {@link FlowFieldService}。
 *
 * <p>两类产物共用同一组 id 化路径（{@code /{id}/description|velocity/{frame}|mask}）：
 * <ul>
 *   <li><b>内置工况</b>：{@code 84000dc} 这种（{@code /cases} 列出来），只盖住大区 DEM 的约 8%；</li>
 *   <li><b>按场景</b>：{@code f-fab276146b9c} 这种（{@code /scenes} 列出来，{@code /scene/resolve} 解析），
 *       覆盖整片 DEM。</li>
 * </ul>
 * id 形态决定去哪个前缀取，所以前端一套请求就能同时用两类。
 *
 * <p>路径全部挂在 {@code /v0/bank/flow-field/**} 下，因此自动落入
 * {@code SecurityConfig} 的 {@code anyRequest().authenticated()}，与其他业务接口同等鉴权。
 */
@RestController
@RequestMapping("/v0/bank/flow-field")
public class FlowFieldController {

    private final FlowFieldService flowFieldService;

    public FlowFieldController(FlowFieldService flowFieldService) {
        this.flowFieldService = flowFieldService;
    }

    /** 可用工况清单（流量档 × 潮型）。前端据此渲染下拉，不要在前端硬编码工况表。 */
    @GetMapping("/cases")
    public Map<String, Object> listCases() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cases", flowFieldService.listCases());
        payload.put("frames", flowFieldService.frameCount());
        return payload;
    }

    /**
     * 已生成的「按场景」流场清单（每个场景覆盖一整片 DEM）。
     *
     * <p>与 {@link #listCases()} 的区别：工况是 2024-08 一次性算的固定素材，只盖住大区 DEM 的约 8%；
     * 场景是按任务所属 DEM 的范围从库里点云现生成的，覆盖整片 DEM。前端应优先用场景。
     */
    @GetMapping("/scenes")
    public Map<String, Object> listScenes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenes", flowFieldService.listScenes());
        return payload;
    }

    /**
     * 按「任务所属的 DEM + 水情」解析出场景 id。
     *
     * <p>场景 id 是哈希，前端与 Java 都算不出来（规则只在模型服务的 Python 里实现一次），
     * 所以这里做一层翻译。{@code found=false} 说明这片 DEM 的这个水情档还没生成过。
     */
    @GetMapping("/scene/resolve")
    public Map<String, Object> resolveScene(
            @RequestParam("tiff_key") String tiffKey,
            @RequestParam("water_qs") String waterQs,
            @RequestParam("tidal_level") String tidalLevel,
            @RequestParam(value = "resolution", required = false) Integer resolution) {
        return flowFieldService.resolveScene(tiffKey, waterQs, tidalLevel, resolution);
    }

    /** 工况的流场描述（extent / flow_boundary / texture_size / 26 帧路径）。 */
    @GetMapping("/{case_id}/description")
    public Map<String, Object> description(@PathVariable("case_id") String caseId) {
        return flowFieldService.loadDescription(caseId);
    }

    /**
     * 第 frame 帧的流速纹理。像素里装的是归一化 u/v 的 float32 字节，
     * 反归一化要用 description 里的 {@code flow_boundary}。</p>
     *
     * <p>纹理是静态产物，让浏览器缓存住：26 帧逐帧播放时每帧只会真正下载一次。
     */
    @GetMapping(value = "/{case_id}/velocity/{frame}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> velocityFrame(
            @PathVariable("case_id") String caseId,
            @PathVariable("frame") int frame) {
        return png(flowFieldService.loadVelocityFrame(caseId, frame));
    }

    /** 水域掩膜（16 位索引对：水域格指自己、陆域格指一个随机水域格）。 */
    @GetMapping(value = "/{case_id}/mask", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> areaMask(@PathVariable("case_id") String caseId) {
        return png(flowFieldService.loadAreaMask(caseId));
    }

    private ResponseEntity<byte[]> png(byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(Duration.ofHours(12)).cachePublic().getHeaderValue())
                .contentType(MediaType.IMAGE_PNG)
                .body(content);
    }
}
