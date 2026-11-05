package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.RiverSurfaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 长江河面范围图层。
 *
 * <p>与 {@link TiffBoundsController} 同一套响应形状（{@code success / data}），
 * 前端可以直接复用「取 GeoJSON 范围」的写法。
 *
 * <p>图层的几何来源必须与断面校验一致（`river_yangtze`），改动这里之前
 * 先确认 {@code SectionValidationService.validateIntersectsRiver} 用的还是同一张表。
 */
@RestController
@RequestMapping("/v0/bank")
public class RiverSurfaceController {

    private static final Logger log = LoggerFactory.getLogger(RiverSurfaceController.class);
    private final RiverSurfaceService riverSurfaceService;

    public RiverSurfaceController(RiverSurfaceService riverSurfaceService) {
        this.riverSurfaceService = riverSurfaceService;
    }

    /**
     * @param tol 简化容差（米，作用于 3857 平面）。缺省 0 = 不简化（与判定几何逐点一致），
     *            上限 200 m 仅作网络受限时的逃生口。
     */
    @GetMapping("/river-surface")
    public Map<String, Object> getRiverSurface(@RequestParam(name = "tol", required = false) Double tol) {
        double tolM = RiverSurfaceService.clampTolerance(tol);
        log.info("[api] fetching river surface as GeoJSON, tol={}m", tolM);

        Map<String, Object> payload = riverSurfaceService.loadGeoJson(tolM);
        Object data = payload.get("data");
        Object meta = payload.get("meta");

        return Map.of(
                "success", true,
                "data", data,
                "meta", meta
        );
    }
}
