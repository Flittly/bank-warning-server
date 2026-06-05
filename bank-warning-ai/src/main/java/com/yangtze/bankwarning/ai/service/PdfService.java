package com.yangtze.bankwarning.ai.service;

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

        try{
            List<String> cmd = new ArrayList<>();
            cmd.add("uv");
            cmd.add("run");
            cmd.add("python");
            cmd.add(scriptFile.getAbsolutePath());
            cmd.add(filePath);

            log.info("[pdf] executing: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = readOutput(process);
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if(!completed){
                process.destroyForcibly();
                return Map.of("success", false, "error", "脚本执行超时");
            }

            log.info("[pdf] exit code: {}, output length: {}", process.exitValue(), output.length());

            if (process.exitValue() != 0) {
                return Map.of("success", false, "error", "脚本执行失败: " + output);
            }

            return Map.of("success", true, "content", output);

        } catch (Exception e) {
            log.error("[pdf] execute failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private File resolveScript(String skillName, String scriptName){
        // 1. 尝试绝对路径
        File direct = new File(scriptName);
        if(direct.isAbsolute() && direct.exists()) return direct;

        String skill = skillName == null || skillName.isBlank() ? "pdf" : skillName.trim();

        // 2. 优先 .skills-cache/<skill>/scripts/ （Nacos/classpath 物化目录）
        File fromCache = new File(Paths.get(cacheDir, skill, "scripts").toString(), scriptName);
        if(fromCache.exists()) return fromCache;

        // 3. Fallback: classpath 老目录（兼容本地 pdf skill 没经过物化的情况）
        File fromFallback = new File(fallbackScriptsDir, scriptName);
        if(fromFallback.exists()) return fromFallback;

        // 4. Fallback: user.dir/src/main/resources/skills/pdf/scripts/
        File fromUserDir = new File(System.getProperty("user.dir"),
                "src/main/resources/skills/pdf/scripts/" + scriptName);
        if (fromUserDir.exists()) return fromUserDir;

        return null;
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
