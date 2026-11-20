package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.TiffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/v0/bank")
public class TiffController {

    private static final Logger log = LoggerFactory.getLogger(TiffController.class);
    private final TiffService tiffService;

    public TiffController(TiffService tiffService) {
        this.tiffService = tiffService;
    }

    @GetMapping("/tiffs")
    public Map<String, Object> listTiffs() {
        return Map.of(
                "success", true,
                "tiffs", tiffService.listTiffs()
        );
    }

    @PostMapping("/tiffs/upload")
    public Map<String, Object> uploadTiff(
            @RequestPart("file") MultipartFile file,
            @RequestParam("segment") String segment,
            @RequestParam("year") String year,
            @RequestParam("timepoint") String timepoint
    ) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        log.info("[tiff-upload-api] receiving upload, segment={}, year={}, timepoint={}, filename={}",
                segment, year, timepoint, file.getOriginalFilename());
        return tiffService.uploadTiff(file, segment, year, timepoint);
    }

    /**
     * DEM 栅格预览：前端双击资源树里的 DEM 时，把它铺到地图上。
     *
     * <p>有效高程按灰度渲染（低暗高亮），nodata / -9999 渲染为纯红。地理范围与色标
     * 随图放在 {@code X-Dem-*} 响应头里，前端不需要再查一次边界接口。
     *
     * <p>为什么不是一个裸 URL 给地图直接用：Mapbox 内部走 {@code new Request(url)}，
     * 请求对象被解析成绝对地址，前端统一加 Bearer 的拦截器不会命中它 ——
     * 所以前端必须先 fetch 成 blob 再喂给地图。
     */
    @GetMapping(value = "/tiffs/preview", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewTiff(
            @RequestParam("tiff_key") String tiffKey,
            @RequestParam(value = "max_size", required = false) Integer maxSize
    ) {
        return tiffService.fetchPreview(tiffKey, maxSize);
    }

    @DeleteMapping("/tiffs")
    public Map<String, Object> deleteTiff(@RequestParam("tiff_key") String tiffKey) {
        if (tiffKey == null || tiffKey.isBlank()) {
            throw new IllegalArgumentException("tiff_key is required");
        }
        log.info("[tiff-delete-api] deleting tiff, tiff_key={}", tiffKey);
        return tiffService.deleteTiff(tiffKey);
    }
}
