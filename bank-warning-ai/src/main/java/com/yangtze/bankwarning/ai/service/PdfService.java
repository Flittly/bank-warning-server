package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.PythonImportScanner;
import com.yangtze.bankwarning.ai.security.SkillSandboxExecutor;
import com.yangtze.bankwarning.ai.security.SkillMetadata;
import com.yangtze.bankwarning.ai.security.SkillOutputValidator;
import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import com.yangtze.bankwarning.ai.service.SkillGovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}")
    private String cacheDir;

    @Value("${app.ai.pdf.fallback-scripts-dir:${user.dir}/src/main/resources/skills/pdf/scripts}")
    private String fallbackScriptsDir;

    private final PythonImportScanner importScanner;
    private final SkillSandboxExecutor sandboxExecutor;
    private final SkillGovernanceService governance;
    private final SkillOutputValidator outputValidator;

    public PdfService(PythonImportScanner importScanner,
                      SkillSandboxExecutor sandboxExecutor,
                      SkillGovernanceService governance,
                      SkillOutputValidator outputValidator) {
        this.importScanner = importScanner;
        this.sandboxExecutor = sandboxExecutor;
        this.governance = governance;
        this.outputValidator = outputValidator;
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

        // 阶段三：读取 skill 元数据（版本 / 权限 / 输出契约），执行前做治理裁决
        Path skillDir = Paths.get(cacheDir, skillName);
        SkillMetadata metadata = SkillMetadata.parse(skillDir);
        SkillGovernanceService.GovernanceDecision decision =
                governance.evaluate(skillName, metadata.getVersion(), metadata.getPermissions());
        if (!decision.isAllowed()) {
            String detail = String.join("; ", decision.reasons());
            governance.recordAudit(skillName, metadata.getVersion(), "EXECUTE_BLOCKED", detail, true);
            log.warn("[pdf] skill 被治理策略拒绝: skill={} version={} reasons={}", skillName, metadata.getVersion(), detail);
            return Map.of("success", false, "error", "Skill 执行被治理策略拒绝: " + detail);
        }
        for (String warning : decision.warnings()) {
            log.warn("[pdf] skill 治理警告: skill={} version={} {}", skillName, metadata.getVersion(), warning);
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

        try {
            Path pdfAbs = pdfFile.toAbsolutePath();
            // 统一走阶段二沙箱执行器：环境白名单 + 强超时 + 输出上限 + audit hook/rlimit
            SkillSandboxExecutor.SandboxRequest sandboxReq = SkillSandboxExecutor.SandboxRequest.builder()
                    .script(scriptFile.toPath().toAbsolutePath())
                    .skillDir(skillDir.toAbsolutePath())
                    .args(List.of(pdfAbs.toString()))
                    // 输入文件目录只读挂载：脚本能读 PDF，但不能写回宿主任意路径
                    .readRoots(List.of(pdfAbs.getParent()))
                    .extraEnv(Map.of("PYTHONIOENCODING", "utf-8"))
                    .useUvProject(true)
                    .build();
            SkillSandboxExecutor.SandboxResult result = sandboxExecutor.execute(sandboxReq);

            if (result.isTimedOut()) {
                return Map.of("success", false, "error",
                        "脚本执行超时（沙箱 " + result.getMode() + "，" + result.getDurationMs() + "ms 后强杀）");
            }
            if (result.isOutputTruncated()) {
                log.warn("[pdf] 脚本输出超过沙箱上限，已截断");
            }
            if (result.getExitCode() != 0) {
                governance.recordAudit(skillName, metadata.getVersion(), "EXECUTE_FAILED",
                        "exit=" + result.getExitCode() + " stderr=" + trimTo(result.getStderr(), 500), true);
                return Map.of("success", false, "error",
                        "脚本执行失败: " + trimTo(result.getStderr(), 2000));
            }

            // 阶段三：输出契约校验，失败即拒绝，绝不透传不可信输出
            SkillOutputValidator.ValidationResult outputCheck = outputValidator.validate(metadata, result);
            if (!outputCheck.isValid()) {
                governance.recordAudit(skillName, metadata.getVersion(), "OUTPUT_INVALID",
                        outputCheck.getReason(), true);
                log.warn("[pdf] 脚本输出未通过校验: skill={} version={} reason={}",
                        skillName, metadata.getVersion(), outputCheck.getReason());
                return Map.of("success", false, "error", "脚本输出未通过校验: " + outputCheck.getReason());
            }
            governance.recordAudit(skillName, metadata.getVersion(), "EXECUTE_OK",
                    "exit=" + result.getExitCode() + " durationMs=" + result.getDurationMs(), false);
            return Map.of("success", true, "content", result.getStdout());
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

        String skill = skillName == null || skillName.isBlank() ? "pdf" : skillName.strip();

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

    private static String trimTo(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...(truncated)";
    }
}
