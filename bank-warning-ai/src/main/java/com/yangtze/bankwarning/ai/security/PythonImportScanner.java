package com.yangtze.bankwarning.ai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python 脚本静态 import 扫描器。
 *
 * 解析 .py 文件中的顶层 import 语句，与危险模块黑名单比对。
 * 若 SKILL.md frontmatter 声明了 permissions（如 network、subprocess），
 * 则对应的黑名单项放行。
 */
@Component
public class PythonImportScanner {

    private static final Logger log = LoggerFactory.getLogger(PythonImportScanner.class);

    /** 默认危险模块黑名单（可按模块名精确匹配，也可按前缀匹配） */
    public static final List<String> DEFAULT_FORBIDDEN = List.of(
            "ctypes", "multiprocessing", "socket", "subprocess",
            "urllib", "http", "ftplib", "telnetlib", "ftputil", "paramiko", "shutil"
    );

    private static final Pattern IMPORT_STMT =
            Pattern.compile("^\\s*(?:import|from)\\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\\s+import)?", Pattern.MULTILINE);
    // 逐行匹配 "import X" / "from X import Y"，捕获被 import 的顶层模块名 X

    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*", Pattern.DOTALL);
    // 匹配 SKILL.md 顶部的 YAML frontmatter（--- 包裹的段落），用于读取 permissions 声明

    private final Set<String> forbidden;
    private final boolean failOnViolation;

    public PythonImportScanner(
            @Value("${app.ai.skill.verify.forbidden-imports:}") List<String> forbidden,
            @Value("${app.ai.skill.verify.fail-on-violation:true}") boolean failOnViolation) {
        // 配置为空时兜底用默认黑名单，否则用配置覆盖（支持自定义扩充/收窄）
        this.forbidden = new LinkedHashSet<>(forbidden == null || forbidden.isEmpty() ? DEFAULT_FORBIDDEN : forbidden);
        this.failOnViolation = failOnViolation;
    }

    /** 扫描结果：违规 import 列表 + 是否放行 */
    public static final class ScanResult {
        private final List<String> violations;
        private final boolean allowed;

        ScanResult(List<String> violations, boolean allowed) {
            this.violations = violations;
            this.allowed = allowed;
        }

        public List<String> getViolations() {
            return violations;
        }

        public boolean isAllowed() {
            return allowed;
        }
    }

    /**
     * 扫描 skill 目录下所有 .py 文件。
     *
     * @param skillDir         skill 根目录（含 SKILL.md 与 scripts/）
     * @param skillPermissions SKILL.md frontmatter 声明的 permissions（可空）
     * @return 扫描结果
     */
    public ScanResult scanSkillDir(Path skillDir, Collection<String> skillPermissions) {
        Set<String> perms = new HashSet<>(skillPermissions == null ? Set.of() : skillPermissions);
        List<String> allViolations = new ArrayList<>();
        try {
            if (Files.exists(skillDir)) {
                try (var stream = Files.walk(skillDir)) {
                    for (Path p : stream.filter(Files::isRegularFile)
                            .filter(f -> f.toString().endsWith(".py")).toList()) {
                        allViolations.addAll(scanFile(p, perms));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[skill-scan] 扫描目录失败 {}: {}", skillDir, e.getMessage());
        }
        boolean allowed = allViolations.isEmpty() || !failOnViolation;
        if (!allViolations.isEmpty()) {
            log.warn("[skill-scan] 发现潜在危险 import: {}", String.join(", ", allViolations));
        }
        return new ScanResult(allViolations, allowed);
    }

    /**
     * 扫描单个 .py 文件。
     */
    public List<String> scanFile(Path pyFile, Collection<String> skillPermissions) {
        Set<String> perms = new HashSet<>(skillPermissions == null ? Set.of() : skillPermissions);
        List<String> violations = new ArrayList<>();
        try {
            String content = Files.readString(pyFile, StandardCharsets.UTF_8);
            Matcher m = IMPORT_STMT.matcher(content);
            while (m.find()) {
                String imported = m.group(1);
                for (String f : forbidden) {
                    if (isMatch(imported, f) && !permits(perms, f)) {
                        violations.add(pyFile.getFileName() + ": " + imported);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[skill-scan] 读取失败 {}: {}", pyFile, e.getMessage());
        }
        return violations;
    }

    /**
     * 从 SKILL.md 中解析 permissions 字段（YAML frontmatter 数组）。
     * 支持格式：permissions: [network, subprocess] 或
     * permissions:\n  - network\n  - subprocess
     */
    public static Set<String> parsePermissions(String skillMdContent) {
        Set<String> result = new HashSet<>();
        if (skillMdContent == null) return result;
        Matcher fm = FRONTMATTER.matcher(skillMdContent);
        if (!fm.find()) return result;
        String yaml = fm.group(1);
        // 行内数组形式
        Matcher inline = Pattern.compile("^\\s*permissions\\s*:\\s*\\[([^\\]]*)\\]", Pattern.MULTILINE).matcher(yaml);
        if (inline.find()) {
            for (String item : inline.group(1).split(",")) {
                String t = item.trim().replaceAll("[\"'\\[\\]]", "");
                if (!t.isEmpty()) result.add(t.toLowerCase());
            }
            return result;
        }
        // 列表形式
        Matcher block = Pattern.compile("^\\s*permissions\\s*:\\s*\\R", Pattern.MULTILINE).matcher(yaml);
        if (block.find()) {
            String after = yaml.substring(block.end());
            Matcher item = Pattern.compile("^\\s*-\\s*([^\\n\\r]+)", Pattern.MULTILINE).matcher(after);
            while (item.find()) {
                String t = item.group(1).trim().replaceAll("[\"'\\[\\]]", "");
                if (!t.isEmpty()) result.add(t.toLowerCase());
            }
        }
        return result;
    }

    /** 从文件读取 SKILL.md 并解析 permissions */
    public static Set<String> parsePermissions(Path skillDir) {
        Path skillMd = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) return Set.of();
        try {
            return parsePermissions(Files.readString(skillMd, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Set.of();
        }
    }

    private boolean isMatch(String importedModule, String forbidden) {
        // http / urllib 是标准库包，其子模块（http.server、urllib.request 等）同样具备网络能力，
        // 需要单独列出子模块名精确匹配，避免仅匹配到顶层包时漏掉实际发起网络请求的子模块
        if (forbidden.equals("http")) {
            return importedModule.equals("http") || importedModule.equals("http.server")
                    || importedModule.equals("http.client") || importedModule.equals("http.cookiejar");
        }
        if (forbidden.equals("urllib")) {
            return importedModule.equals("urllib") || importedModule.startsWith("urllib.");
        }
        // 其余模块按"完全相等 或 顶层包名前缀"匹配（如 subprocess 覆盖 subprocess.Popen）
        if (importedModule.equals(forbidden)) return true;
        if (importedModule.startsWith(forbidden + ".")) return true;
        return false;
    }

    private boolean permits(Set<String> perms, String forbidden) {
        // permissions 命名空间：network 放行网络类；subprocess/process 放行子进程类
        // 只有 SKILL.md frontmatter 显式声明了对应权限，才允许该类别下的黑名单 import
        if (perms.contains("network") || perms.contains("net")) {
            if (forbidden.equals("socket") || forbidden.equals("urllib") || forbidden.equals("http")
                    || forbidden.equals("ftplib") || forbidden.equals("telnetlib") || forbidden.equals("ftputil")
                    || forbidden.equals("paramiko")) {
                return true;
            }
        }
        if (perms.contains("subprocess") || perms.contains("process")) {
            if (forbidden.equals("subprocess") || forbidden.equals("multiprocessing")) {
                return true;
            }
        }
        return false;
    }

    public boolean isFailOnViolation() {
        return failOnViolation;
    }
}
