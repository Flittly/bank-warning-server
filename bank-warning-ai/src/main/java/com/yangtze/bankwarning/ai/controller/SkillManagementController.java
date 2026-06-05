package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.config.NacosSkillRepositoryHolder;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill 管理端点（仅当 Nacos 启用时生效；未启用时返回 503）。
 *
 * 注意：NacosSkillRepository.save() 是 no-op（SDK 3.2.1 还没出 skill 上传 API），
 * 本 Controller 绕开 SDK，直接调 Nacos admin HTTP API（POST /nacos/v3/admin/ai/skills/upload）。
 */
@RestController
@RequestMapping("/v0/bank/ai/skill")
public class SkillManagementController {

    private static final Logger log = LoggerFactory.getLogger(SkillManagementController.class);
    private final NacosSkillRepositoryHolder holder;

    public SkillManagementController(ObjectProvider<NacosSkillRepositoryHolder> holderProvider) {
        this.holder = holderProvider.getIfAvailable();
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        if (!isReady()) return unavailable();
        List<String> names = holder.getRepository().getAllSkillNames();
        return Map.of("success", true, "source", "nacos", "skills", names);
    }

    @PostMapping("/sync-local")
    public Map<String, Object> syncLocal(@RequestParam(defaultValue = "true") boolean force) {
        if (!isReady()) return unavailable();
        try (ClasspathSkillRepository local = new ClasspathSkillRepository("skills")) {
            List<AgentSkill> skills = local.getAllSkills();
            if (skills.isEmpty()) {
                return Map.of("success", false, "error", "classpath:skills/ 下没有发现任何 skill");
            }
            log.info("[skill] sync-local: 发现 {} 个本地 skill", skills.size());
            List<String> uploaded = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (AgentSkill s : skills) {
                try {
                    String n = holder.uploadSkill(s.getName(), s.getSkillContent(), s.getResources());
                    uploaded.add(n);
                    log.info("[skill] sync-local: uploaded {} (resources={})", n,
                            s.getResources() == null ? 0 : s.getResources().size());
                } catch (Exception e) {
                    failed.add(s.getName() + ": " + e.getMessage());
                    log.error("[skill] sync-local: failed to upload {}", s.getName(), e);
                }
            }
            return Map.of(
                    "success", failed.isEmpty(),
                    "synced", uploaded.size(),
                    "names", uploaded,
                    "failed", failed);
        } catch (Exception e) {
            log.error("[skill] sync-local 失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody PublishRequest req) {
        if (!isReady()) return unavailable();
        if (req.name == null || req.name.isBlank() || req.content == null) {
            return Map.of("success", false, "error", "name 和 content 必填");
        }
        try {
            String uploadedName = holder.uploadSkill(req.name, req.content, req.resources);
            log.info("[skill] publish name={} uploadedAs={}", req.name, uploadedName);
            return Map.of("success", true, "name", uploadedName);
        } catch (Exception e) {
            log.error("[skill] publish failed name={}", req.name, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        if (!isReady()) return unavailable();
        boolean ok = holder.getRepository().delete(name);
        log.info("[skill] delete name={} success={}", name, ok);
        return Map.of("success", ok, "name", name);
    }

    @GetMapping("/{name}")
    public Map<String, Object> get(@PathVariable String name) {
        if (!isReady()) return unavailable();
        AgentSkill skill = holder.getRepository().getSkill(name);
        if (skill == null) return Map.of("success", false, "error", "Skill 不存在");
        return Map.of("success", true, "name", skill.getName(), "description", skill.getDescription());
    }

    private boolean isReady() {
        return holder != null && holder.isAvailable();
    }

    private Map<String, Object> unavailable() {
        return Map.of("success", false, "error", "Nacos 未启用或连接不可用");
    }

    public static class PublishRequest {
        public String name;
        public String description;
        public String content;
        public Boolean force;
        public Map<String, String> resources;
    }
}
