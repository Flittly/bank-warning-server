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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    /** 允许处理的输入文件后缀（小写比较） */
    private static final Set<String> ALLOWED_SUFFIXES = Set.of(".pdf", ".docx");

    @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}")
    private String cacheDir;

    @Value("${app.ai.pdf.fallback-scripts-dir:${user.dir}/src/main/resources/skills/pdf/scripts}")
    private String fallbackScriptsDir;

    /**
     * 允许读取的根目录，逗号分隔；留空时使用默认集合（项目工作目录 + 系统临时目录）。
     * 用于把 filePath 约束在可控范围内，避免 process_pdf 变成"读任意文件"的原语。
     */
    @Value("${app.ai.pdf.allowed-read-roots:}")
    private String allowedReadRootsRaw;

    private final PythonImportScanner importScanner;
    private final SkillSandboxExecutor sandboxExecutor;
    private final SkillGovernanceService governance;
    private final SkillOutputValidator outputValidator;
    private final SkillVersionService versionService;

    public PdfService(PythonImportScanner importScanner,
                      SkillSandboxExecutor sandboxExecutor,
                      SkillGovernanceService governance,
                      SkillOutputValidator outputValidator,
                      SkillVersionService versionService) {
        this.importScanner = importScanner;
        this.sandboxExecutor = sandboxExecutor;
        this.governance = governance;
        this.outputValidator = outputValidator;
        this.versionService = versionService;
    }

    public Map<String, Object> processPdf(String skillName, String scriptName, String filePath){
        log.info("[pdf] processPdf, skill={}, script={}, file={}", skillName, scriptName, filePath);
        String skill = skillName == null || skillName.isBlank() ? "pdf" : skillName.strip();

        // 输入文件围栏：后缀白名单 + 读取根目录白名单（此前只检查"存在"，Agent 可借此读任意文件）
        Path pdfFile;
        try {
            pdfFile = resolveInputFile(filePath);
        } catch (IllegalArgumentException e) {
            log.warn("[pdf] 拒绝非法输入文件: {} ({})", filePath, e.getMessage());
            return Map.of("success", false, "error", "输入文件不被允许: " + e.getMessage());
        }
        if(!Files.isRegularFile(pdfFile)){
            return Map.of("success", false, "error", "PDF 文件不存在: " + filePath);
        }

        //解析脚本路径
        File scriptFile = resolveScript(skill, scriptName);
        if(scriptFile == null || !scriptFile.exists()){
            return Map.of("success", false,
                "error", "脚本不存在: skill=" + skillName + ", script=" + scriptName
                    + "（查找目录：" + cacheDir + "/" + skill + "/scripts, "
                    + fallbackScriptsDir + "）");
        }

        // 阶段三：读取 skill 元数据（版本 / 权限 / 输出契约），执行前做治理裁决
        Path skillDir = resolveActiveSkillDir(skill);
        SkillMetadata metadata = SkillMetadata.parse(skillDir);
        SkillGovernanceService.GovernanceDecision decision =
                governance.evaluate(skill, metadata.getVersion(), metadata.getPermissions());
        if (!decision.isAllowed()) {
            String detail = String.join("; ", decision.reasons());
            governance.recordAudit(skill, metadata.getVersion(), "EXECUTE_BLOCKED", detail, true);
            log.warn("[pdf] skill 被治理策略拒绝: skill={} version={} reasons={}", skill, metadata.getVersion(), detail);
            return Map.of("success", false, "error", "Skill 执行被治理策略拒绝: " + detail);
        }
        for (String warning : decision.warnings()) {
            log.warn("[pdf] skill 治理警告: skill={} version={} {}", skill, metadata.getVersion(), warning);
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
                    // 只读挂载"这一个输入文件"本身，而不是它所在的整个目录：
                    // 避免脚本顺带拿到同目录下其它文件的读取权
                    .readRoots(List.of(pdfAbs))
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
                governance.recordAudit(skill, metadata.getVersion(), "EXECUTE_FAILED",
                        "exit=" + result.getExitCode() + " stderr=" + trimTo(result.getStderr(), 500), true);
                return Map.of("success", false, "error",
                        "脚本执行失败: " + trimTo(result.getStderr(), 2000));
            }

            // 阶段三：输出契约校验，失败即拒绝，绝不透传不可信输出
            SkillOutputValidator.ValidationResult outputCheck = outputValidator.validate(metadata, result);
            if (!outputCheck.isValid()) {
                governance.recordAudit(skill, metadata.getVersion(), "OUTPUT_INVALID",
                        outputCheck.getReason(), true);
                log.warn("[pdf] 脚本输出未通过校验: skill={} version={} reason={}",
                        skill, metadata.getVersion(), outputCheck.getReason());
                return Map.of("success", false, "error", "脚本输出未通过校验: " + outputCheck.getReason());
            }
            governance.recordAudit(skill, metadata.getVersion(), "EXECUTE_OK",
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

        // 1. 优先当前生效版本的 .skills-cache/<skill>/<version>/scripts/
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
            Path base = resolveActiveSkillDir(skill).resolve("scripts").toAbsolutePath().normalize();
            return SkillPathGuard.safeResolve(base, scriptName).toFile();
        } catch (IllegalArgumentException e) {
            log.warn("[pdf] 非法脚本路径: skill={} script={} reason={}", skill, scriptName, e.getMessage());
            return null;
        }
    }

    /** 解析当前生效版本的 skill 目录：ACTIVE 版本目录 → 版本目录兜底 → 旧布局 .skills-cache/<skill> */
    private Path resolveActiveSkillDir(String skill) {
        return versionService.resolveActiveDir(skill)
                .orElseGet(() -> Paths.get(cacheDir, skill).toAbsolutePath().normalize());
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

    /**
     * 校验并解析待处理的输入文件路径。
     *
     * 安全约束（此前完全缺失，只检查了"文件存在"）：
     *   1. 后缀必须在白名单内（.pdf / .docx），杜绝把它当"读任意文件"的原语；
     *   2. 路径必须落在允许的读取根目录之内，越界一律拒绝。
     *
     * 允许根默认取"项目工作目录 + 系统临时目录"（后者是 /knowledge/upload 落临时文件的位置），
     * 可用 app.ai.pdf.allowed-read-roots 显式覆盖（逗号分隔）。
     *
     * @throws IllegalArgumentException 路径为空、后缀不允许、或落在允许根之外
     */
    private Path resolveInputFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path candidate = Paths.get(filePath).toAbsolutePath().normalize();

        String name = candidate.getFileName() == null
                ? "" : candidate.getFileName().toString().toLowerCase(Locale.ROOT);
        if (ALLOWED_SUFFIXES.stream().noneMatch(name::endsWith)) {
            throw new IllegalArgumentException("仅支持 PDF / DOCX 文件: " + name);
        }

        List<Path> roots = allowedReadRoots();
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("文件不在允许读取的目录内: " + candidate + "（允许根：" + roots + "）");
    }

    /** 解析允许读取的根目录列表；配置为空时回退到默认集合 */
    private List<Path> allowedReadRoots() {
        List<Path> roots = new ArrayList<>();
        if (allowedReadRootsRaw != null && !allowedReadRootsRaw.isBlank()) {
            for (String part : allowedReadRootsRaw.split(",")) {
                if (!part.isBlank()) {
                    roots.add(Paths.get(part.strip()).toAbsolutePath().normalize());
                }
            }
        }
        if (roots.isEmpty()) {
            roots.add(Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp != null && !tmp.isBlank()) {
                roots.add(Paths.get(tmp).toAbsolutePath().normalize());
            }
        }
        return roots;
    }

    private static String trimTo(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...(truncated)";
    }
}
