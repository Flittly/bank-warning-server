package com.yangtze.bankwarning.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v0/bank/ai")
public class ReportsController {

    private static final Logger log = LoggerFactory.getLogger(ReportsController.class);
    private static final Pattern FILENAME_PTN = Pattern.compile("^report_(.+?)_(\\d{8}_\\d{6})\\.md$");

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    @Value("${app.ai.visualization.script-dir:../bank-model-server}")
    private String scriptDir;

    @GetMapping("/reports/{filename}/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable String filename) {
        File mdFile = new File(new File(outputDir, "reports"), filename);
        if (!mdFile.exists()) return ResponseEntity.notFound().build();
        try {
            Path tmpDir = Files.createTempDirectory("docx-");
            Path docxFile = tmpDir.resolve(filename.replace(".md", ".docx"));
            ProcessBuilder pb = new ProcessBuilder(
                "uv", "run", "python", "util/md2docx.py",
                mdFile.getAbsolutePath(), docxFile.toString()
            );
            pb.directory(new File(scriptDir).getAbsoluteFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) {
                return ResponseEntity.status(500).body(("导出失败: " + out).getBytes(StandardCharsets.UTF_8));
            }
            byte[] docx = Files.readAllBytes(docxFile);
            Files.deleteIfExists(docxFile);
            Files.deleteIfExists(tmpDir);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace(".md", ".docx") + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(docx);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(("导出异常: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/reports")
    public Map<String, Object> listReports() {
        File dir = new File(outputDir, "reports");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        List<Map<String, Object>> list = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("filename", f.getName());
                item.put("size", f.length());
                item.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date(f.lastModified())));
                Matcher m = FILENAME_PTN.matcher(f.getName());
                if (m.matches()) {
                    item.put("taskId", m.group(1));
                    item.put("timestamp", m.group(2));
                }
                list.add(item);
            }
        }
        return Map.of("success", true, "reports", list);
    }

    @GetMapping("/reports/{filename}")
    public Map<String, Object> getReport(@PathVariable String filename) {
        File file = new File(new File(outputDir, "reports"), filename);
        if (!file.exists()) {
            return Map.of("success", false, "error", "文件不存在");
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return Map.of("success", true, "filename", filename, "content", content);
        } catch (IOException e) {
            return Map.of("success", false, "error", "读取失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/reports/{filename}")
    public Map<String, Object> deleteReport(@PathVariable String filename) {
        File file = new File(new File(outputDir, "reports"), filename);
        if (!file.exists()) {
            return Map.of("success", false, "error", "文件不存在");
        }
        boolean deleted = file.delete();
        return Map.of("success", deleted, "filename", filename, "deleted", deleted);
    }

    @PutMapping("/reports/{filename}")
    public Map<String, Object> updateReport(@PathVariable String filename,
                                            @RequestBody Map<String, String> body) {
        File dir = new File(outputDir, "reports");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, filename);
        String content = body.get("content");
        if (content == null) {
            return Map.of("success", false, "error", "content 不能为空");
        }
        try {
            java.nio.file.Files.writeString(file.toPath(), content,
                    java.nio.charset.StandardCharsets.UTF_8);
            return Map.of("success", true, "filename", filename, "updated", true);
        } catch (IOException e) {
            return Map.of("success", false, "error", "保存失败: " + e.getMessage());
        }
    }

    @PostMapping("/reports/save")
    public Map<String, Object> saveReport(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return Map.of("success", false, "error", "content 不能为空");
        }
        String taskId = body.getOrDefault("taskId", "chat");
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "report_" + taskId + "_" + ts + ".md";
        File dir = new File(outputDir, "reports");
        if (!dir.exists()) dir.mkdirs();
        try {
            java.nio.file.Files.writeString(new File(dir, filename).toPath(), content,
                    java.nio.charset.StandardCharsets.UTF_8);
            return Map.of("success", true, "filename", filename);
        } catch (IOException e) {
            return Map.of("success", false, "error", "保存失败: " + e.getMessage());
        }
    }
}
