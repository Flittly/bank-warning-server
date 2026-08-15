package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.PythonImportScanner;
import com.yangtze.bankwarning.ai.security.SkillMetadata;
import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill 缓存物化服务。
 *
 * 统一管理 skill 脚本的执行缓存（.skills-cache/<skillName>/）：
 *   - 启动时物化 AgentScope 仓库里的 skill（String 资源，二进制带 base64: 前缀）；
 *   - 下载后按字节直接刷新缓存，立即生效，无需重启。
 * 物化过程统一做路径防逃逸和静态 import 扫描。
 */
@Service
public class SkillCacheService {

    private static final Logger log = LoggerFactory.getLogger(SkillCacheService.class);

    /** 版本目录名白名单：只允许字母数字、点、下划线、连字符，禁止路径分隔符与 .. */
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    /** skill 名白名单：与版本号同规则，防止把用户可控的 skill 名拼进缓存路径 */
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path cacheBase;
    private final PythonImportScanner scanner;

    public SkillCacheService(
            @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}") String cacheDir,
            PythonImportScanner scanner) {
        this.cacheBase = Paths.get(cacheDir).toAbsolutePath().normalize();
        this.scanner = scanner;
    }

    public Path getSkillDir(String skillName) {
        return cacheBase.resolve(normalizeSkillName(skillName)).toAbsolutePath().normalize();
    }

    /**
     * 校验 skill 名：非空、只含 [A-Za-z0-9._-]、不能是 "." / ".."。
     * 所有缓存路径（含递归删除）都以它为前缀，必须挡住路径逃逸。
     */
    public static String normalizeSkillName(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skill 名不能为空");
        }
        String value = skillName.strip();
        if (!SKILL_NAME_PATTERN.matcher(value).matches() || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("非法 skill 名: " + skillName);
        }
        return value;
    }

    /**
     * 版本化 skill 目录：.skills-cache/<skillName>/<version>/
     * 版本号来自 SKILL.md frontmatter，仍需白名单校验防止路径逃逸。
     */
    public Path getVersionDir(String skillName, String version) {
        String normalized = normalizeVersion(version);
        return getSkillDir(skillName).resolve(normalized).toAbsolutePath().normalize();
    }

    /**
     * 校验版本号：非空、只含 [A-Za-z0-9._-]、不能是 "." / ".."。
     */
    public static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.0";
        }
        String value = version.strip();
        if (!VERSION_PATTERN.matcher(value).matches() || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("非法 skill 版本号: " + version);
        }
        return value;
    }

    /**
     * 版本化物化：按字节写入 .skills-cache/<skillName>/<version>/，
     * 路径防逃逸 + 静态扫描，立即生效。
     *
     * @throws IllegalStateException 物化失败（含路径逃逸）
     */
    public Path materializeVersioned(String skillName, String version, Map<String, byte[]> files) {
        Path base = getVersionDir(skillName, version);
        try {
            Files.createDirectories(base);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                Path target = SkillPathGuard.safeResolve(base, entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
            scanSkill(base, skillName);
            log.info("[SkillCache] 物化完成 {}@{} -> {} ({} files)", skillName, version, base, files.size());
            return base;
        } catch (IOException e) {
            log.error("[SkillCache] 物化失败 {}@{}: {}", skillName, version, e.getMessage());
            throw new IllegalStateException("Skill 缓存物化失败: " + skillName + "@" + version, e);
        }
    }

    /**
     * 旧布局物化（兼容历史版本）：写入 .skills-cache/<skillName>/，
     * 仅当执行链路无法解析出版本时回退使用。
     *
     * @throws IllegalStateException 物化失败（含路径逃逸）
     */
    public Path materialize(String skillName, Map<String, byte[]> files) {
        Path base = getSkillDir(skillName);
        try {
            Files.createDirectories(base);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                Path target = SkillPathGuard.safeResolve(base, entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
            scanSkill(base, skillName);
            log.info("[SkillCache] 物化完成 {} -> {} ({} files)", skillName, base, files.size());
            return base;
        } catch (IOException e) {
            log.error("[SkillCache] 物化失败 {}: {}", skillName, e.getMessage());
            throw new IllegalStateException("Skill 缓存物化失败: " + skillName, e);
        }
    }

    /**
     * 版本化启动物化：AgentScope 资源形态（String，二进制带 base64: 前缀），
     * 解码后按字节写入，避免文本往返损坏二进制文件。
     */
    public Path materializeResourcesVersioned(String skillName, String version, Map<String, String> resources) {
        if (resources == null || resources.isEmpty()) {
            return getVersionDir(skillName, version);
        }
        return materializeVersioned(skillName, version, decodeResources(resources));
    }

    /**
     * 启动物化（兼容历史调用）：自动从 resources 的 SKILL.md 解析版本，按版本目录物化。
     */
    public Path materializeResources(String skillName, Map<String, String> resources) {
        return materializeResourcesVersioned(skillName, resolveVersion(resources), resources);
    }

    /**
     * 列出某 skill 已物化的版本目录名（跳过校验失败的异常目录）。
     */
    public List<String> listMaterializedVersions(String skillName) {
        Path base = getSkillDir(skillName);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<String> versions = new ArrayList<>();
        try (Stream<Path> stream = Files.list(base)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> {
                        String name = dir.getFileName().toString();
                        try {
                            normalizeVersion(name);
                            versions.add(name);
                        } catch (IllegalArgumentException ignored) {
                            // 旧布局下 .skills-cache/<skill>/ 直接放 SKILL.md，没有版本子目录，跳过
                        }
                    });
        } catch (IOException e) {
            log.warn("[SkillCache] 列出版本目录失败 {}: {}", skillName, e.getMessage());
        }
        versions.sort((a, b) -> compareVersions(b, a));
        return versions;
    }

    /**
     * 版本号自然排序：按点分段、段内数字优先数值比较（1.10.0 > 1.9.0），
     * 避免字符串字典序把 1.10.0 排在 1.9.0 前面。
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            Integer ia = parseIntQuietly(sa);
            Integer ib = parseIntQuietly(sb);
            if (ia != null && ib != null) {
                int c = ia.compareTo(ib);
                if (c != 0) {
                    return c;
                }
            } else {
                int c = sa.compareTo(sb);
                if (c != 0) {
                    return c;
                }
            }
        }
        return 0;
    }

    private static Integer parseIntQuietly(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 判断某版本目录是否存在且含 SKILL.md */
    public boolean hasVersion(String skillName, String version) {
        return Files.isRegularFile(getVersionDir(skillName, version).resolve("SKILL.md"));
    }

    /** 删除某版本目录（仅允许删除缓存根内的版本子目录） */
    public void deleteVersionDir(String skillName, String version) {
        Path dir = getVersionDir(skillName, version);
        if (!Files.exists(dir)) {
            return;
        }
        Path base = getSkillDir(skillName);
        if (!dir.getParent().equals(base.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("版本目录越界: " + dir);
        }
        deleteRecursively(dir);
        log.info("[SkillCache] 已删除版本目录 {}@{} -> {}", skillName, version, dir);
    }

    private Map<String, byte[]> decodeResources(Map<String, String> resources) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String content = entry.getValue();
            if (content == null) {
                continue;
            }
            byte[] data = content.startsWith("base64:")
                    ? Base64.getDecoder().decode(content.substring("base64:".length()))
                    : content.getBytes(StandardCharsets.UTF_8);
            files.put(entry.getKey(), data);
        }
        return files;
    }

    /** 从资源集合里找 SKILL.md 解析版本号，缺失按 0.0.0 */
    private static String resolveVersion(Map<String, String> resources) {
        if (resources != null) {
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                String key = entry.getKey().replace('\\', '/');
                if (key.equals("SKILL.md") || key.equals("./SKILL.md")) {
                    return SkillMetadata.parse(entry.getValue()).getVersion();
                }
            }
        }
        return SkillMetadata.DEFAULT_VERSION;
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void scanSkill(Path base, String skillName) {
        try {
            var scanResult = scanner.scanSkillDir(base, PythonImportScanner.parsePermissions(base));
            if (!scanResult.getViolations().isEmpty()) {
                log.warn("[SkillCache] skill '{}' import violations: {}", skillName,
                        String.join(", ", scanResult.getViolations()));
            }
        } catch (Exception e) {
            log.warn("[SkillCache] scan skill '{}' failed: {}", skillName, e.getMessage());
        }
    }
}
