package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.service.NacosSkillRepositoryHolder;
import com.yangtze.bankwarning.ai.service.SkillApprovalService;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 管理接口（列表 / 上传 / 下载 / 发布 / 同步 / 删除）。
 *
 * <p>鉴权边界（修复「写权限宽于审批权限」）：
 * <ul>
 *   <li><b>只读</b>（{@code list} / {@code get}）：登录用户即可，供结果页与工作台展示技能清单；</li>
 *   <li><b>写</b>（{@code upload} / {@code sync-local} / {@code publish} / {@code delete}）：
 *       一律 ADMIN。这些端点会改动 Nacos 上的、以及本地即将执行的 skill 内容，
 *       属于「改变将要在机器上运行的代码」的动作，门槛必须不低于
 *       {@link SkillGovernanceController} 的审批（同为 ADMIN）。</li>
 * </ul>
 *
 * <p>{@code download} 暂保持登录用户可用：它拉取的内容仍要过
 * {@code SkillContentVerifier} 完整性校验与治理裁决，且按 SKILL.md 声明的权限
 * 自动生成待审批记录，审批通过前权限类能力无法执行。是否进一步收紧待评估。
 */
@RestController
@RequestMapping("/v0/bank/ai/skill")
public class SkillManagementController {

    private static final Logger log = LoggerFactory.getLogger(SkillManagementController.class);
    private final NacosSkillRepositoryHolder holder;
    private final List<AgentSkillRepository> allRepos;
    private final SkillApprovalService approvalService;

    public SkillManagementController(ObjectProvider<NacosSkillRepositoryHolder> holderProvider,
                                      @Qualifier("agentSkillRepositories") List<AgentSkillRepository> allRepos,
                                      SkillApprovalService approvalService) {
        this.holder = holderProvider.getIfAvailable();
        this.allRepos = allRepos;
        this.approvalService = approvalService;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        List<Map<String, Object>> skills = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> nacoNames = holder != null ? new java.util.HashSet<>(holder.listNacosSkillNames()) : java.util.Set.of();
        for (AgentSkillRepository repo : allRepos) {
            if (repo instanceof ClasspathSkillRepository) {
                for (AgentSkill skill : repo.getAllSkills()) {
                    String name = skill.getName();
                    seen.add(name);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("description", skill.getDescription());
                    item.put("source", nacoNames.contains(name) ? "synced" : "local");
                    item.putAll(approvalService.listGovernanceStatus(name));
                    skills.add(item);
                }
            }
        }
        // Nacos-独有的 skills（不在 local 中）
        for (String name : nacoNames) {
            if (!seen.contains(name)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("description", holder.getNacosSkillDesc(name));
                item.put("source", "nacos");
                item.putAll(approvalService.listGovernanceStatus(name));
                skills.add(item);
            }
        }
        return Map.of("success", true, "skills", skills);
    }

    @PostMapping("/download/{name}")
    public Map<String, Object> downloadSkill(@PathVariable String name) {
        if (!isReady()) return unavailable();
        try {
            holder.downloadSkillToLocal(name);
            return Map.of("success", true, "name", name, "downloaded", true);
        } catch (Exception e) {
            log.error("[skill] download failed for '{}': {}", name, e.getMessage());
            return Map.of("success", false, "error", "下载失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload/{name}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public Map<String, Object> uploadOneSkill(@PathVariable String name) {
        if (!isReady()) return unavailable();
        try (ClasspathSkillRepository local = new ClasspathSkillRepository("skills")) {
            AgentSkill skill = local.getSkill(name);
            if (skill == null) return Map.of("success", false, "error", "本地 Skill 不存在");
            String fullMd = "---\nname: " + skill.getName()
                    + "\ndescription: " + (skill.getDescription() != null ? skill.getDescription() : "")
                    + "\n---\n\n" + (skill.getSkillContent() != null ? skill.getSkillContent() : "");
            holder.uploadSkill(skill.getName(), fullMd, skill.getResources());
            approvalService.createPendingFromContent(name, skill.getSkillContent());
            return Map.of("success", true, "name", name, "uploaded", true);
        } catch (Exception e) {
            log.error("[skill] upload failed for '{}': {}", name, e.getMessage());
            return Map.of("success", false, "error", "上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/sync-local")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
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
                    String fullMd = "---\nname: " + s.getName()
                            + "\ndescription: " + (s.getDescription() != null ? s.getDescription() : "")
                            + "\n---\n\n" + (s.getSkillContent() != null ? s.getSkillContent() : "");
                    String n = holder.uploadSkill(s.getName(), fullMd, s.getResources());
                    approvalService.createPendingFromContent(s.getName(), s.getSkillContent());
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public Map<String, Object> publish(@RequestBody PublishRequest req) {
        if (!isReady()) return unavailable();
        if (req.name == null || req.name.isBlank() || req.content == null) {
            return Map.of("success", false, "error", "name 和 content 必填");
        }
        try {
            String uploadedName = holder.uploadSkill(req.name, req.content, req.resources);
            approvalService.createPendingFromContent(req.name, req.content);
            log.info("[skill] publish name={} uploadedAs={}", req.name, uploadedName);
            return Map.of("success", true, "name", uploadedName);
        } catch (Exception e) {
            log.error("[skill] publish failed name={}", req.name, e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
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
