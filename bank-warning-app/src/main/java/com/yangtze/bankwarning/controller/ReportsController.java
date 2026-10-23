package com.yangtze.bankwarning.controller;

import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import com.yangtze.bankwarning.ai.service.ReportPackageService;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/v0/bank/ai")
public class ReportsController {

    private static final Logger log = LoggerFactory.getLogger(ReportsController.class);
    private static final Pattern FILENAME_PTN = Pattern.compile("^report_(.+?)_(\\d{8}_\\d{6})\\.md$");
    /** AI 出图结果：与报告共用一个目录、同一套查看/删除/导出能力，只是前缀区分来源。 */
    private static final Pattern VIZ_FILENAME_PTN = Pattern.compile("^viz_(.+?)_(\\d{8}_\\d{6})\\.md$");
    /** 出图 md 里引用的图片目录：/v0/bank/ai/viz-output/&lt;dir&gt;/&lt;file&gt; */
    private static final Pattern VIZ_IMG_DIR_PTN = Pattern.compile("/v0/bank/ai/viz-output/([A-Za-z0-9_.-]+)/");
    /** 只允许删除形如 viz_xxx 的目录，绝不放任何别的路径进删除逻辑 */
    private static final Pattern SAFE_VIZ_DIR_PTN = Pattern.compile("^viz_[A-Za-z0-9_.-]+$");

    /** md2docx 导出的墙钟上限：脚本卡死时不再永久占用一个请求线程 */
    private static final long EXPORT_TIMEOUT_SECONDS = 60L;

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String outputDir;

    @Value("${app.ai.visualization.script-dir:../bank-model-server}")
    private String scriptDir;

    private final ReportPackageService reportPackageService;

    public ReportsController(ReportPackageService reportPackageService) {
        this.reportPackageService = reportPackageService;
    }

    /**
     * 导出报告。
     *
     * <p>报告按经纬度分幅生成时，本次任务的输出目录里还留着各分区的断面详情 CSV
     * （见 {@code PartitionSectionCsvService}）。此时导出返回一个 zip：
     * {@code 报告.docx + 分区1_断面详情.csv + … + 分区N_断面详情.csv}，
     * 让「报告」与其各分区断面详情是同一次导出的产物。
     * 没有分区 CSV（不分幅 / 老报告）时行为与历史版本一致——直接返回 docx。
     */
    @GetMapping("/reports/{filename}/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable String filename) {
        File mdFile;
        try {
            mdFile = resolveReportFile(filename);
        } catch (IllegalArgumentException e) {
            log.warn("[reports] 拒绝非法报告文件名: {} ({})", filename, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(("非法文件名: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
        if (!mdFile.exists()) return ResponseEntity.notFound().build();
        String safeName = mdFile.getName();
        try {
            Path tmpDir = Files.createTempDirectory("docx-");
            Path docxFile = tmpDir.resolve(safeName.replace(".md", ".docx"));
            Path logFile = tmpDir.resolve("md2docx.log");
            ProcessBuilder pb = new ProcessBuilder(
                "uv", "run", "python", "util/md2docx.py",
                mdFile.getAbsolutePath(), docxFile.toString()
            );
            pb.directory(new File(scriptDir).getAbsoluteFile());
            pb.redirectErrorStream(true);
            // 输出重定向到文件：既避免子进程因管道缓冲写满而阻塞，也让超时后仍能取回诊断信息
            pb.redirectOutput(logFile.toFile());
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();
            boolean finished = p.waitFor(EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String out = readLogQuietly(logFile);
            if (!finished) {
                // 脚本卡死时强杀，不再让一个请求线程被永久占住
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                log.error("[reports] md2docx 超时（{}s）: {}", EXPORT_TIMEOUT_SECONDS, safeName);
                return ResponseEntity.status(500)
                        .body(("导出超时（超过 " + EXPORT_TIMEOUT_SECONDS + " 秒）: " + out).getBytes(StandardCharsets.UTF_8));
            }
            if (p.exitValue() != 0) {
                return ResponseEntity.status(500).body(("导出失败: " + out).getBytes(StandardCharsets.UTF_8));
            }
            byte[] docx = Files.readAllBytes(docxFile);
            File reportDir = reportPackageService.resolveReportDir(new File(outputDir), safeName);
            List<File> sectionCsvs = reportPackageService.listPartitionCsvs(reportDir);
            byte[] zip = reportPackageService.zipWithCsvs(
                    safeName.replace(".md", ".docx"), docx, sectionCsvs);
            Files.deleteIfExists(docxFile);
            Files.deleteIfExists(logFile);
            Files.deleteIfExists(tmpDir);

            if (zip != null) {
                String zipName = safeName.replace(".md", "_导出包.zip");
                log.info("[reports] 导出报告包：{}（含 {} 个分区断面详情 CSV）", zipName, sectionCsvs.size());
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ReportPackageService.contentDisposition(zipName))
                    .header("X-Section-Csv-Count", String.valueOf(sectionCsvs.size()))
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zip);
            }
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ReportPackageService.contentDisposition(safeName.replace(".md", ".docx")))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(docx);
        } catch (Exception e) {
            log.error("[reports] 导出异常: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(("导出异常: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 列出「报告查看」里的全部条目。
     *
     * <p>目录里有两类 md：AI 报告的 report_&lt;taskId&gt;_&lt;ts&gt;.md，和 AI 出图结果的
     * viz_&lt;taskId&gt;_&lt;ts&gt;.md。两者同目录、同寿命、同查看器，靠 {@code kind} 区分
     * （viz = 出图结果）。排序统一按文件修改时间倒序，最新生成的在最上面。
     */
    @GetMapping("/reports")
    public Map<String, Object> listReports() {
        File dir = new File(outputDir, "reports");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        List<Map<String, Object>> list = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                Map<String, Object> item = new LinkedHashMap<>();
                String name = f.getName();
                item.put("filename", name);
                item.put("size", f.length());
                item.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date(f.lastModified())));
                Matcher vizMatcher = VIZ_FILENAME_PTN.matcher(name);
                Matcher reportMatcher = FILENAME_PTN.matcher(name);
                if (vizMatcher.matches()) {
                    item.put("kind", "viz");
                    item.put("taskId", vizMatcher.group(1));
                    item.put("timestamp", vizMatcher.group(2));
                } else {
                    item.put("kind", "report");
                    if (reportMatcher.matches()) {
                        item.put("taskId", reportMatcher.group(1));
                        item.put("timestamp", reportMatcher.group(2));
                    }
                }
                list.add(item);
            }
        }
        return Map.of("success", true, "reports", list);
    }

    @GetMapping("/reports/{filename}")
    public Map<String, Object> getReport(@PathVariable String filename) {
        File file;
        try {
            file = resolveReportFile(filename);
        } catch (IllegalArgumentException e) {
            log.warn("[reports] 拒绝非法报告文件名: {} ({})", filename, e.getMessage());
            return Map.of("success", false, "error", "非法文件名: " + e.getMessage());
        }
        if (!file.exists()) {
            return Map.of("success", false, "error", "文件不存在");
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return Map.of("success", true, "filename", file.getName(), "content", content);
        } catch (IOException e) {
            return Map.of("success", false, "error", "读取失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/reports/{filename}")
    public Map<String, Object> deleteReport(@PathVariable String filename) {
        File file;
        try {
            file = resolveReportFile(filename);
        } catch (IllegalArgumentException e) {
            log.warn("[reports] 拒绝非法报告文件名: {} ({})", filename, e.getMessage());
            return Map.of("success", false, "error", "非法文件名: " + e.getMessage());
        }
        log.info("[reports] 删除报告: {}", file.getAbsolutePath());

        if (!file.exists()) {
            log.warn("[reports] 文件不存在: {}", file.getAbsolutePath());
            return Map.of("success", false, "error", "文件不存在");
        }

        // 1. 删除 .md 报告文件
        // 出图结果要先留一份正文：图片目录是从正文里的 URL 反查出来的，删完就读不到了。
        boolean isViz = file.getName().startsWith("viz_");
        String vizContent = null;
        if (isViz) {
            try {
                vizContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("[reports] 读取出图记录失败，将只删 md 不动图片目录: {} ({})", file.getName(), e.getMessage());
            }
        }
        boolean deleted = file.delete();
        log.info("[reports] 报告文件删除: {} -> {}", file.getName(), deleted);

        // 2. 同步清理关联的输出目录（子图 + 分区断面详情 CSV；格式 report_{taskId}_{timestamp}）
        File reportDir = reportPackageService.resolveReportDir(new File(outputDir), file.getName());
        if (reportDir != null) {
            boolean dirDeleted = deleteDirectory(reportDir);
            log.info("[reports] 报告输出目录删除（含子图与分区 CSV）: {} -> {}",
                    reportDir.getAbsolutePath(), dirDeleted);
        }

        // 3. 出图记录：连它引用的 viz_ 图片目录一起清掉，避免留下一堆没人引用的 PNG
        if (isViz && vizContent != null) {
            for (String dirName : referencedVizDirs(vizContent)) {
                if (!SAFE_VIZ_DIR_PTN.matcher(dirName).matches()) continue;
                try {
                    File vizDir = SkillPathGuard.safeResolve(
                            new File(outputDir).toPath().toAbsolutePath().normalize(), dirName).toFile();
                    boolean ok = deleteDirectory(vizDir);
                    log.info("[reports] 出图图片目录删除: {} -> {}", dirName, ok);
                } catch (IllegalArgumentException e) {
                    log.warn("[reports] 跳过非法出图目录: {} ({})", dirName, e.getMessage());
                }
            }
        }

        return Map.of("success", deleted, "filename", file.getName(), "deleted", deleted);
    }

    /** 从出图记录的正文里反查它引用了哪些 viz_ 目录（去重，保持出现顺序） */
    private Set<String> referencedVizDirs(String content) {
        Set<String> dirs = new LinkedHashSet<>();
        if (content == null || content.isBlank()) return dirs;
        Matcher m = VIZ_IMG_DIR_PTN.matcher(content);
        while (m.find()) {
            dirs.add(m.group(1));
        }
        return dirs;
    }

    // 递归删除目录
    private boolean deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        return dir.delete();
    }

    @PutMapping("/reports/{filename}")
    public Map<String, Object> updateReport(@PathVariable String filename,
                                            @RequestBody Map<String, String> body) {
        File file;
        try {
            file = resolveReportFile(filename);
        } catch (IllegalArgumentException e) {
            log.warn("[reports] 拒绝非法报告文件名: {} ({})", filename, e.getMessage());
            return Map.of("success", false, "error", "非法文件名: " + e.getMessage());
        }
        String content = body.get("content");
        if (content == null) {
            return Map.of("success", false, "error", "content 不能为空");
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.writeString(file.toPath(), content,
                    java.nio.charset.StandardCharsets.UTF_8);
            return Map.of("success", true, "filename", file.getName(), "updated", true);
        } catch (IOException e) {
            return Map.of("success", false, "error", "保存失败: " + e.getMessage());
        }
    }

    /**
     * 把 URL 路径变量 filename 解析为 reports 目录下的真实文件。
     *
     * 安全约束：只接受"单个文件名"——任何路径分隔符（/ \）、盘符冒号、
     * 或以 . / .. 结尾的形式一律拒绝；再经 {@link SkillPathGuard#safeResolve}
     * 做 normalize 后的目录包含校验兜底，确保结果必然落在 reports 目录内。
     * 读、写、删、导出四个端点共用此方法，避免"某个端点漏了校验"。
     *
     * @throws IllegalArgumentException 文件名为空或试图逃逸出 reports 目录
     */
    private File resolveReportFile(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String name = filename.strip();
        boolean hasSeparator = name.indexOf('/') >= 0 || name.indexOf('\\') >= 0;
        // Windows 盘符相对路径（如 C:foo）带 root 但不是绝对路径，resolve 时会被特殊处理，一并挡掉
        boolean hasDriveColon = name.indexOf(':') >= 0;
        if (hasSeparator || hasDriveColon || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("文件名不允许包含路径分隔符或盘符: " + name);
        }
        Path base = new File(outputDir, "reports").toPath().toAbsolutePath().normalize();
        return SkillPathGuard.safeResolve(base, name).toFile();
    }

    /** 读取子进程日志，最多 8KB（防脚本疯狂输出打爆内存），失败时返回空串 */
    private String readLogQuietly(Path logFile) {
        try {
            if (!Files.isRegularFile(logFile)) return "";
            long size = Files.size(logFile);
            int limit = (int) Math.min(size, 8192L);
            if (limit <= 0) return "";
            byte[] buf = new byte[limit];
            try (var in = Files.newInputStream(logFile)) {
                int read = in.readNBytes(buf, 0, limit);
                return new String(buf, 0, read, StandardCharsets.UTF_8)
                        + (size > limit ? "...(truncated)" : "");
            }
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 保存一份 md 到「报告查看」目录。
     *
     * <p>{@code kind} 决定前缀：缺省 / "report" = AI 报告（report_…），"viz" = AI 出图结果（viz_…）。
     * 两者共用同一目录、同一份查看/删除能力，列表里靠 kind 区分。
     */
    @PostMapping("/reports/save")
    public Map<String, Object> saveReport(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return Map.of("success", false, "error", "content 不能为空");
        }
        boolean viz = "viz".equalsIgnoreCase(body.getOrDefault("kind", "report"));
        String taskId = body.getOrDefault("taskId", "chat");
        if (taskId == null || taskId.isBlank()) taskId = "chat";
        taskId = taskId.strip();
        // taskId 来自请求体且会被拼进文件名，同样必须挡住路径分隔符 / 盘符
        if (taskId.indexOf('/') >= 0 || taskId.indexOf('\\') >= 0 || taskId.indexOf(':') >= 0) {
            log.warn("[reports] 拒绝非法 taskId: {}", taskId);
            return Map.of("success", false, "error", "taskId 不允许包含路径分隔符或盘符");
        }
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = (viz ? "viz_" : "report_") + taskId + "_" + ts + ".md";
        File dir = new File(outputDir, "reports");
        File file;
        try {
            file = SkillPathGuard.safeResolve(dir.toPath(), filename).toFile();
        } catch (IllegalArgumentException e) {
            log.warn("[reports] 拒绝非法保存路径: {} ({})", filename, e.getMessage());
            return Map.of("success", false, "error", "非法文件名: " + e.getMessage());
        }
        if (!dir.exists()) dir.mkdirs();
        try {
            java.nio.file.Files.writeString(file.toPath(), content,
                    java.nio.charset.StandardCharsets.UTF_8);
            return Map.of("success", true, "filename", file.getName());
        } catch (IOException e) {
            return Map.of("success", false, "error", "保存失败: " + e.getMessage());
        }
    }
}
