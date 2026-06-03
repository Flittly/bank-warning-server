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

    @Value("${app.ai.pdf.scripts-dir:${user.dir}/src/main/resources/skills/pdf/scripts}")
    private String scriptsDir;

    @Value("${app.ai.pdf.timeout-seconds:120}")
    private int timeoutSeconds;

    public Map<String, Object> processPdf(String scriptName, String filePath){
        log.info("[pdf] processPdf, script={}, file={}", scriptName, filePath);

        Path pdfFile = Paths.get(filePath);
        if(!Files.exists(pdfFile)){
            return Map.of("success", false, "error", "PDF 文件不存在: " + filePath);
        }

        //解析脚本路径
        File scriptFile = resolveScript(scriptName);
        if(scriptFile == null || !scriptFile.exists()){
            return Map.of("success", false, "error", "脚本不存在: " + scriptName);
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

    private File resolveScript(String scriptName){
        // 尝试绝对路径
        File direct = new File(scriptName);
        if(direct.isAbsolute() && direct.exists()) return direct;

        //尝试相对scriptsDir
        File fromConfig = new File(scriptsDir, scriptName);
        if(fromConfig.exists()) return fromConfig;

        //尝试相对于user.dir
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
