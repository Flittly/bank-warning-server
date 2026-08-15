package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.SkillMetadata;
import com.yangtze.bankwarning.ai.store.SkillVersionStore;
import com.yangtze.bankwarning.ai.store.SkillVersionStore.SkillVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 版本服务（档位二 · 本地多版本共存）。
 *
 * 职责：
 *   1. 下载/物化时把版本写入 .skills-cache/<skill>/<version>/ 并登记到 skill_versions；
 *   2. 提供“当前生效版本目录”的解析：ACTIVE 版本目录 → 版本目录最新兜底 → 旧布局兜底；
 *   3. 管理接口：版本列表 / 激活 / 隔离 / 删除（删除同时清掉缓存目录）。
 */
@Service
public class SkillVersionService {

    private static final Logger log = LoggerFactory.getLogger(SkillVersionService.class);

    private final SkillVersionStore store;
    private final SkillCacheService cacheService;

    public SkillVersionService(SkillVersionStore store, SkillCacheService cacheService) {
        this.store = store;
        this.cacheService = cacheService;
    }

    /**
     * 下载后注册：物化到版本目录 + 登记版本并激活（旧 ACTIVE 自动降级 RETIRED）。
     */
    public SkillVersion registerDownload(String skillName, String version, String source,
                                         Map<String, byte[]> files, String updatedBy) {
        String normalized = SkillCacheService.normalizeVersion(version);
        cacheService.materializeVersioned(skillName, normalized, files);
        SkillVersion record = store.registerOrActivate(skillName, normalized, source, updatedBy);
        log.info("[skill-version] 下载注册 {}@{} source={} status={}", skillName, normalized, source, record.status());
        return record;
    }

    /**
     * 启动物化（classpath 等资源形态）：从 resources 的 SKILL.md 解析版本后按版本物化并登记。
     */
    public SkillVersion registerResources(String skillName, Map<String, String> resources, String source,
                                          String updatedBy) {
        String version = extractVersion(resources);
        cacheService.materializeResourcesVersioned(skillName, version, resources);
        SkillVersion record = store.registerOrActivate(skillName, version, source, updatedBy);
        log.info("[skill-version] 启动物化注册 {}@{} source={} status={}", skillName, version, source, record.status());
        return record;
    }

    /**
     * 解析某 skill 当前生效的版本目录：
     *   1. skill_versions 中 ACTIVE 版本且目录存在；
     *   2. 无登记时，取已物化版本目录中最大的版本号（兼容升级前就存在的缓存）；
     *   3. 都无则回退旧布局 .skills-cache/<skill>/（含 SKILL.md 的旧目录）。
     */
    public Optional<Path> resolveActiveDir(String skillName) {
        Optional<SkillVersion> active = store.findActive(skillName);
        if (active.isPresent()) {
            String version = active.get().version();
            if (cacheService.hasVersion(skillName, version)) {
                return Optional.of(cacheService.getVersionDir(skillName, version));
            }
            log.warn("[skill-version] ACTIVE 版本目录缺失 {}@{}，尝试兜底", skillName, version);
        }
        List<String> versions = cacheService.listMaterializedVersions(skillName);
        if (!versions.isEmpty()) {
            return Optional.of(cacheService.getVersionDir(skillName, versions.get(0)));
        }
        Path legacy = cacheService.getSkillDir(skillName);
        if (Files.isDirectory(legacy)) {
            return Optional.of(legacy);
        }
        return Optional.empty();
    }

    /** 当前生效版本号（目录兜底时从 SKILL.md 解析，解析不到按 0.0.0） */
    public Optional<String> resolveActiveVersion(String skillName) {
        Optional<SkillVersion> active = store.findActive(skillName);
        if (active.isPresent()) {
            return Optional.of(active.get().version());
        }
        return resolveActiveDir(skillName)
                .map(dir -> SkillMetadata.parse(dir).getVersion());
    }

    public List<SkillVersion> listVersions(String skillName) {
        return store.listVersions(skillName);
    }

    public List<SkillVersion> listAllVersions() {
        return store.listAll();
    }

    public Optional<SkillVersion> findByVersion(String skillName, String version) {
        return store.findByVersion(skillName, SkillCacheService.normalizeVersion(version));
    }

    /** 激活某版本（旧 ACTIVE 自动降级 RETIRED），返回是否成功 */
    public boolean activate(String skillName, String version, String updatedBy) {
        String normalized = SkillCacheService.normalizeVersion(version);
        if (!cacheService.hasVersion(skillName, normalized)) {
            log.warn("[skill-version] 激活失败：版本目录不存在 {}@{}", skillName, normalized);
            return false;
        }
        boolean ok = store.activate(skillName, normalized, updatedBy);
        if (ok) {
            log.info("[skill-version] 激活 {}@{} by {}", skillName, version, updatedBy);
        }
        return ok;
    }

    /** 隔离某版本（执行前裁决会拒绝），返回是否成功 */
    public boolean quarantine(String skillName, String version, String updatedBy) {
        return setStatus(skillName, version, SkillVersionStore.STATUS_QUARANTINED, updatedBy);
    }

    /** 解除隔离并激活，返回是否成功 */
    public boolean unquarantine(String skillName, String version, String updatedBy) {
        return activate(skillName, version, updatedBy);
    }

    /** 删除版本：先删数据库记录，再删缓存目录，返回是否删除了记录 */
    public boolean delete(String skillName, String version, String updatedBy) {
        String normalized = SkillCacheService.normalizeVersion(version);
        boolean removed = store.delete(skillName, normalized);
        cacheService.deleteVersionDir(skillName, normalized);
        if (removed) {
            log.info("[skill-version] 删除 {}@{} by {}", skillName, normalized, updatedBy);
        }
        return removed;
    }

    private boolean setStatus(String skillName, String version, String status, String updatedBy) {
        boolean ok = store.setStatus(skillName, SkillCacheService.normalizeVersion(version), status, updatedBy);
        if (ok) {
            log.info("[skill-version] 设置 {}@{} -> {} by {}", skillName, version, status, updatedBy);
        }
        return ok;
    }

    /** 从资源集合里解析版本号：优先 SKILL.md 内容，缺失按 0.0.0 */
    private static String extractVersion(Map<String, String> resources) {
        if (resources == null) {
            return SkillMetadata.DEFAULT_VERSION;
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            normalized.put(entry.getKey().replace('\\', '/'), entry.getValue());
        }
        String skillMd = normalized.get("SKILL.md");
        if (skillMd != null) {
            return SkillMetadata.parse(skillMd).getVersion();
        }
        return SkillMetadata.DEFAULT_VERSION;
    }
}
