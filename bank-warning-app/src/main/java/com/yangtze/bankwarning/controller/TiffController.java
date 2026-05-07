package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.service.TiffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @DeleteMapping("/tiffs")
    public Map<String, Object> deleteTiff(@RequestParam("tiff_key") String tiffKey) {
        if (tiffKey == null || tiffKey.isBlank()) {
            throw new IllegalArgumentException("tiff_key is required");
        }
        log.info("[tiff-delete-api] deleting tiff, tiff_key={}", tiffKey);
        return tiffService.deleteTiff(tiffKey);
    }
}
