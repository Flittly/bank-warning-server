package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.PythonImportScanner;
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

    private final Path cacheBase;
    private final PythonImportScanner scanner;

    public SkillCacheService(
            @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}") String cacheDir,
            PythonImportScanner scanner) {
        this.cacheBase = Paths.get(cacheDir).toAbsolutePath().normalize();
        this.scanner = scanner;
    }

    public Path getSkillDir(String skillName) {
        return cacheBase.resolve(skillName).toAbsolutePath().normalize();
    }

    /**
     * 下载后刷新：按字节写入缓存，路径防逃逸 + 静态扫描，立即生效。
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
     * 启动物化：AgentScope 资源形态（String，二进制带 base64: 前缀），
     * 解码后按字节写入，避免文本往返损坏二进制文件。
     */
    public Path materializeResources(String skillName, Map<String, String> resources) {
        if (resources == null || resources.isEmpty()) {
            return getSkillDir(skillName);
        }
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
        return materialize(skillName, files);
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
