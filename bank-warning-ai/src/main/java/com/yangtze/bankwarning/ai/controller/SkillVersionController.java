package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.SkillVersionService;
import com.yangtze.bankwarning.ai.store.SkillVersionStore.SkillVersion;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 版本管理接口（档位二 · 本地多版本共存）。
 *
 * 全部要求 ADMIN 角色：
 *   - 查看某 skill（或全部）的版本记录与状态；
 *   - 激活某个已下载版本（旧 ACTIVE 自动降级 RETIRED）；
 *   - 隔离 / 解除隔离（隔离的版本执行前裁决直接拒绝）；
 *   - 删除版本（数据库记录 + 缓存目录一并删除）。
 */
@RestController
@RequestMapping("/v0/admin/skill-versions")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class SkillVersionController {

    private final SkillVersionService versionService;

    public SkillVersionController(SkillVersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String skillName) {
        List<SkillVersion> versions = (skillName == null || skillName.isBlank())
                ? versionService.listAllVersions()
                : versionService.listVersions(skillName.strip());
        return Map.of("success", true, "versions", versions);
    }

    @GetMapping("/{skillName}/active")
    public Map<String, Object> active(@PathVariable String skillName) {
        Optional<SkillVersion> active = versionService.listVersions(skillName).stream()
                .filter(v -> "ACTIVE".equals(v.status()))
                .findFirst();
        if (active.isPresent()) {
            return Map.of("success", true, "version", active.get());
        }
        // Map.of 不允许 null 值，用 LinkedHashMap 组装
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("version", null);
        result.put("fallback", versionService.resolveActiveVersion(skillName).orElse(null));
        return result;
    }

    @PostMapping("/{skillName}/{version}/activate")
    public Map<String, Object> activate(@PathVariable String skillName, @PathVariable String version) {
        boolean ok = versionService.activate(skillName, version, operator());
        return ok ? Map.of("success", true, "skillName", skillName, "version", version)
                : Map.of("success", false, "error", "版本不存在或激活失败: " + skillName + "@" + version);
    }

    @PostMapping("/{skillName}/{version}/quarantine")
    public Map<String, Object> quarantine(@PathVariable String skillName, @PathVariable String version) {
        boolean ok = versionService.quarantine(skillName, version, operator());
        return ok ? Map.of("success", true, "skillName", skillName, "version", version)
                : Map.of("success", false, "error", "版本不存在或隔离失败: " + skillName + "@" + version);
    }

    @PostMapping("/{skillName}/{version}/unquarantine")
    public Map<String, Object> unquarantine(@PathVariable String skillName, @PathVariable String version) {
        boolean ok = versionService.unquarantine(skillName, version, operator());
        return ok ? Map.of("success", true, "skillName", skillName, "version", version)
                : Map.of("success", false, "error", "版本不存在或解除隔离失败: " + skillName + "@" + version);
    }

    @DeleteMapping("/{skillName}/{version}")
    public Map<String, Object> delete(@PathVariable String skillName, @PathVariable String version) {
        boolean ok = versionService.delete(skillName, version, operator());
        return ok ? Map.of("success", true, "skillName", skillName, "version", version)
                : Map.of("success", false, "error", "版本记录不存在: " + skillName + "@" + version);
    }

    private static String operator() {
        String name = SecurityUtils.getCurrentUsername();
        return name == null ? "system" : name;
    }
}
