package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.TiffBoundsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v0/bank")
public class TiffBoundsController {

    private static final Logger log = LoggerFactory.getLogger(TiffBoundsController.class);
    private final TiffBoundsService tiffBoundsService;

    public TiffBoundsController(TiffBoundsService tiffBoundsService) {
        this.tiffBoundsService = tiffBoundsService;
    }

    @GetMapping("/tiff-bounds")
    public Map<String, Object> getTiffBounds() {
        log.info("[api] fetching tiff bounds as GeoJSON");
        return Map.of(
                "success", true,
                "data", tiffBoundsService.listTiffBoundsAsGeoJson()
        );
    }
}
