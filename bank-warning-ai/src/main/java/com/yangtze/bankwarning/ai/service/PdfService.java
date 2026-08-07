package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.PythonImportScanner;
import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}")
    private String cacheDir;

    @Value("${app.ai.pdf.fallback-scripts-dir:${user.dir}/src/main/resources/skills/pdf/scripts}")
    private String fallbackScriptsDir;

    @Value("${app.ai.pdf.timeout-seconds:120}")
    private int timeoutSeconds;

    private final PythonImportScanner importScanner;

    public PdfService(PythonImportScanner importScanner) {
        this.importScanner = importScanner;
    }

    public Map<String, Object> processPdf(String skillName, String scriptName, String filePath){
        log.info("[pdf] processPdf, skill={}, script={}, file={}", skillName, scriptName, filePath);

        Path pdfFile = Paths.get(filePath);
        if(!Files.exists(pdfFile)){
            return Map.of("success", false, "error", "PDF 文件不存在: " + filePath);
        }

        //解析脚本路径
        File scriptFile = resolveScript(skillName, scriptName);
        if(scriptFile == null || !scriptFile.exists()){
            return Map.of("success", false,
                "error", "脚本不存在: skill=" + skillName + ", script=" + scriptName
                    + "（查找目录：" + cacheDir + "/" + skillName + "/scripts, "
                    + fallbackScriptsDir + "）");
        }

        //执行前静态扫描脚本
        try {
            List<String> violations = importScanner.scanFile(scriptFile.toPath(),
                    PythonImportScanner.parsePermissions(scriptFile.toPath().getParent().getParent()));
            if (!violations.isEmpty() && importScanner.isFailOnViolation()) {
                log.warn("[pdf] 脚本被静态扫描拦截: {} violations={}", scriptFile, String.join(", ", violations));
                return Map.of("success", false, "error", "脚本未通过安全扫描: " + String.join(", ", violations));
            }
            if (!violations.isEmpty()) {
                log.warn("[pdf] 脚本存在潜在危险 import（已放行）: {} violations={}", scriptFile, String.join(", ", violations));
            }
        } catch (Exception e) {
            log.warn("[pdf] 扫描脚本失败，拒绝执行: {} error={}", scriptFile, e.getMessage());
            return Map.of("success", false, "error", "脚本安全扫描失败: " + e.getMessage());
        }

        try{
            Path skillDir = Paths.get(cacheDir, skillName);
            List<String> cmd = new ArrayList<>();
            cmd.add("uv");
            cmd.add("run");
            cmd.add("--quiet");
            cmd.add("--project");
            cmd.add(skillDir.toString());
            cmd.add("python");
            cmd.add(scriptFile.getAbsolutePath());
            cmd.add(filePath);

            log.info("[pdf] executing: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PYTHONIOENCODING", "utf-8");

            Process process = pb.start();
            StringBuilder stdoutSb = new StringBuilder();
            StringBuilder stderrSb = new StringBuilder();
            Thread stdoutThread = new Thread(() -> {
                try { readStreamInto(process.getInputStream(), stdoutSb); } catch (IOException ignored) {}
            });
            Thread stderrThread = new Thread(() -> {
                try { readStreamInto(process.getErrorStream(), stderrSb); } catch (IOException ignored) {}
            });
            stdoutThread.start();
            stderrThread.start();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if(!completed){
                process.destroyForcibly();
                return Map.of("success", false, "error", "脚本执行超时");
            }
            stdoutThread.join(5000);
            stderrThread.join(5000);

            String stdout = stdoutSb.toString().trim();
            String stderr = stderrSb.toString().trim();

            log.info("[pdf] exit code: {}, stdout length: {}, stderr length: {}",
                    process.exitValue(), stdout.length(), stderr.length());

            if (process.exitValue() != 0) {
                return Map.of("success", false, "error", "脚本执行失败: " + stderr);
            }

            return Map.of("success", true, "content", stdout);

        } catch (Exception e) {
            log.error("[pdf] execute failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 解析要执行的脚本路径。
     *
     * 安全约束：脚本必须来自下方三个白名单目录之一，不接受调用方传入的任意路径
     * （杜绝"绝对路径直执行"），且每个目录内都经 SkillPathGuard 防 ../ 逃逸。
     */
    private File resolveScript(String skillName, String scriptName){
        if (scriptName == null || scriptName.isBlank()) return null;

        String skill = skillName == null || skillName.isBlank() ? "pdf" : skillName.trim();

        // 1. 优先 .skills-cache/<skill>/scripts/ （Nacos/classpath 物化目录）
        File fromCache = resolveWithinCache(skill, scriptName);
        if(fromCache != null && fromCache.exists()) return fromCache;

        // 2. Fallback: classpath 老目录（兼容本地 pdf skill 没经过物化的情况）
        File fromFallback = resolveWithin(fallbackScriptsDir, scriptName);
        if(fromFallback != null && fromFallback.exists()) return fromFallback;

        // 3. Fallback: user.dir/src/main/resources/skills/pdf/scripts/
        File fromUserDir = resolveWithin(
                new File(System.getProperty("user.dir"), "src/main/resources/skills/pdf/scripts").getAbsolutePath(),
                scriptName);
        if (fromUserDir != null && fromUserDir.exists()) return fromUserDir;

        return null;
    }

    /** 在 cache 目录内安全解析脚本路径，防 ../ 逃逸 */
    private File resolveWithinCache(String skill, String scriptName){
        try {
            Path base = Paths.get(cacheDir, skill, "scripts").toAbsolutePath().normalize();
            return SkillPathGuard.safeResolve(base, scriptName).toFile();
        } catch (IllegalArgumentException e) {
            log.warn("[pdf] 非法脚本路径: skill={} script={} reason={}", skill, scriptName, e.getMessage());
            return null;
        }
    }

    /** 在任意基础目录内安全解析，防 ../ 逃逸 */
    private File resolveWithin(String baseDir, String scriptName){
        try {
            Path base = Paths.get(baseDir).toAbsolutePath().normalize();
            return SkillPathGuard.safeResolve(base, scriptName).toFile();
        } catch (IllegalArgumentException e) {
            log.warn("[pdf] 非法脚本路径: base={} script={} reason={}", baseDir, scriptName, e.getMessage());
            return null;
        }
    }

    private void readStreamInto(java.io.InputStream is, StringBuilder sb) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
    }
}
