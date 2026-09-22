package com.yangtze.bankwarning.ai.controller;

import com.yangtze.bankwarning.ai.security.SkillContentVerifier;
import com.yangtze.bankwarning.ai.security.SkillSecurityProfile;
import com.yangtze.bankwarning.ai.security.SkillSecuritySettings;
import com.yangtze.bankwarning.ai.service.SkillSecurityService;
import com.yangtze.bankwarning.security.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 安全档位管理接口（阶段一/二/三的统一闸门面板）。
 *
 * <p>补齐此前的能力真空：三层防护的十几个开关过去只存在于 {@code application.yml}，
 * 改一次要重启，且 {@code governance} / {@code sandbox} 两个节点在 yml 里<b>根本不存在</b>，
 * 全靠 {@code @Value} 兜底，运维无从发现。
 *
 * <p>现在：
 * <ul>
 *   <li>{@code GET  /profile} —— 当前档位 + 每一项生效值 + 风险提示（只读）；</li>
 *   <li>{@code POST /profile} —— 套用预设档位（宽松 / 标准 / 严格），保留运维状态；</li>
 *   <li>{@code PATCH /settings} —— 逐项微调（应急）；</li>
 *   <li>{@code POST /kill-switch} —— 一键熔断，立即生效、免重启；</li>
 *   <li>{@code GET  /history} —— 档位与熔断的变更历史（复用审计表）。</li>
 * </ul>
 *
 * <p>全部要求 ADMIN：档位本身就是攻击面，「谁能把它改回宽松」必须与审批同门槛。
 */
@RestController
@RequestMapping("/v0/admin/skill-security")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class SkillSecurityController {

    private static final Logger log = LoggerFactory.getLogger(SkillSecurityController.class);

    private final SkillSecurityService securityService;
    private final SkillContentVerifier contentVerifier;

    public SkillSecurityController(SkillSecurityService securityService,
                                   SkillContentVerifier contentVerifier) {
        this.securityService = securityService;
        this.contentVerifier = contentVerifier;
    }

    /** 当前档位与全部生效值（前端面板的主数据源） */
    @GetMapping("/profile")
    public Map<String, Object> profile() {
        SkillSecuritySettings s = securityService.settings();
        boolean signingKeyConfigured = contentVerifier.isSigningEnabled();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("profile", s.profile().name());
        result.put("profileLabel", s.profile().label());
        result.put("profileDescription", s.profile().description());
        result.put("presetMatches", s.matchesPreset());
        result.put("killSwitch", s.killSwitch());
        result.put("signingKeyConfigured", signingKeyConfigured);
        result.put("updatedBy", s.updatedBy());
        result.put("updatedAt", s.updatedAt());
        result.put("warnings", warnings(s, signingKeyConfigured));
        result.put("stages", stages(s));
        result.put("presets", presetOptions());
        return result;
    }

    /** 套用预设档位 */
    @PostMapping("/profile")
    public Map<String, Object> applyProfile(@RequestBody Map<String, String> body) {
        String raw = body == null ? null : body.get("profile");
        if (raw == null || raw.isBlank()) {
            return Map.of("success", false, "error", "profile 必填（LOOSE / STANDARD / STRICT）");
        }
        SkillSecurityProfile next;
        try {
            next = SkillSecurityProfile.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", "未知档位: " + raw);
        }
        securityService.applyProfile(next, operator());
        return profile();
    }

    /** 逐项微调（只传要改的字段） */
    @PatchMapping("/settings")
    public Map<String, Object> tune(@RequestBody SkillSecurityService.ProfilePatch patch) {
        try {
            securityService.patch(patch == null ? new SkillSecurityService.ProfilePatch(
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null) : patch, operator());
            return profile();
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /** 一键熔断 / 解除，立即生效 */
    @PostMapping("/kill-switch")
    public Map<String, Object> killSwitch(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("enabled");
        if (!(raw instanceof Boolean enabled)) {
            return Map.of("success", false, "error", "enabled 必填（true / false）");
        }
        securityService.setKillSwitch(enabled, operator());
        return profile();
    }

    /** 档位与熔断的变更历史 */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "50") int limit) {
        return Map.of("success", true, "history", securityService.history(limit));
    }

    /* ------------------------------------------------------------------ */

    private List<Map<String, Object>> warnings(SkillSecuritySettings s, boolean signingKeyConfigured) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (s.killSwitch()) {
            warnings.add(warn("全局熔断已开启：所有 skill 执行都会被治理裁决拒绝。"));
        }
        if ("OFF".equals(s.normalizedMode())) {
            warnings.add(warn("沙箱已关闭（OFF）：只保留环境变量白名单、强超时、输出上限三项基础约束，"
                    + "脚本可自由读写宿主文件系统。"));
        }
        if (!s.verifyEnabled()) {
            warnings.add(warn("静态扫描总开关已关闭：阶段一只剩路径防逃逸，脚本里的危险 import 不会被拦截。"));
        }
        if (!s.failOnViolation()) {
            warnings.add(warn("危险 import 仅告警不拦截（fail-on-violation=false）：扫描结果会写审计但放行执行。"));
        }
        if (!s.requireChecksumManifest()) {
            warnings.add(warn("允许无校验清单的 skill zip 落盘：下载内容只做结构校验，不比对 sha256。"));
        }
        if (s.requireSignedManifest() && !signingKeyConfigured) {
            warnings.add(warn("档位要求清单必须带签名，但未配置 SKILL_HMAC_SECRET："
                    + "此时只能校验 sha256 清单（无法验签），签名要求实际上无法强制。"));
        }
        if (!s.allowedVersions().isEmpty()) {
            warnings.add(warn("版本白名单已启用（" + s.allowedVersions().size()
                    + " 项）：清单之外的 skill@version 一律拒绝执行，请确认常用版本都已登记。"));
        }
        if (!s.governanceEnabled()) {
            warnings.add(warn("治理裁决已整体关闭：不再检查熔断、版本状态、隔离清单与权限审批。"));
        }
        if (!s.enforceOutputContract()) {
            warnings.add(warn("输出契约不强制：脚本输出不满足 SKILL.md 声明的契约时仍会透传给下游。"));
        }
        return warnings;
    }

    private static Map<String, Object> warn(String text) {
        return Map.of("level", "warn", "text", text);
    }

    /** 按三层防护分组输出每一项生效值，前端可直接渲染成表 */
    private List<Map<String, Object>> stages(SkillSecuritySettings s) {
        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(stage("阶段一 · 校验与静态扫描", List.of(
                knob("verifyEnabled", "静态扫描总开关", s.verifyEnabled()),
                knob("failOnViolation", "危险 import 发现即拒绝", s.failOnViolation()),
                knob("forbiddenImports", "危险模块黑名单", s.forbiddenImports().size() + " 项"),
                knob("requireChecksumManifest", "缺校验清单即拒绝", s.requireChecksumManifest()),
                knob("requireSignedManifest", "强制清单签名", s.requireSignedManifest()))));
        stages.add(stage("阶段二 · 执行隔离", List.of(
                knob("sandboxMode", "沙箱档位", s.normalizedMode()),
                knob("sandboxTimeoutSeconds", "执行超时", s.sandboxTimeoutSeconds() + " 秒"),
                knob("sandboxMaxOutputBytes", "输出上限", humanBytes(s.sandboxMaxOutputBytes())),
                knob("sandboxMemoryMb", "内存上限（docker 档生效）", s.sandboxMemoryMb() + " MB"),
                knob("sandboxCpuSeconds", "CPU 时间上限", s.sandboxCpuSeconds() + " 秒"),
                knob("sandboxPidsLimit", "进程数上限（docker 档生效）", s.sandboxPidsLimit()))));
        stages.add(stage("阶段三 · 治理闭环", List.of(
                knob("governanceEnabled", "治理裁决总开关", s.governanceEnabled()),
                knob("killSwitch", "全局熔断（应急）", s.killSwitch()),
                knob("failOnUnapprovedPermission", "权限未审批即拒绝", s.failOnUnapprovedPermission()),
                knob("enforceOutputContract", "输出契约强制校验", s.enforceOutputContract()),
                knob("allowedVersions", "版本白名单", s.allowedVersions().isEmpty()
                        ? "未启用" : s.allowedVersions().size() + " 项"),
                knob("quarantinedVersions", "隔离版本清单", s.quarantinedVersions().isEmpty()
                        ? "空" : s.quarantinedVersions().size() + " 项"))));
        return stages;
    }

    private static Map<String, Object> stage(String title, List<Map<String, Object>> items) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("title", title);
        stage.put("items", items);
        return stage;
    }

    private static Map<String, Object> knob(String key, String label, Object value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("value", value);
        return item;
    }

    /** 三档预设的对照预览，供前端单选卡片展示"这一档会把什么改成什么" */
    private List<Map<String, Object>> presetOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (SkillSecurityProfile p : SkillSecurityProfile.values()) {
            SkillSecuritySettings preset = SkillSecuritySettings.preset(p);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("name", p.name());
            option.put("label", p.label());
            option.put("description", p.description());
            option.put("sandboxMode", preset.sandboxMode());
            option.put("sandboxTimeoutSeconds", preset.sandboxTimeoutSeconds());
            option.put("sandboxMaxOutputBytes", preset.sandboxMaxOutputBytes());
            option.put("forbiddenImportCount", preset.forbiddenImports().size());
            option.put("failOnViolation", preset.failOnViolation());
            option.put("requireChecksumManifest", preset.requireChecksumManifest());
            option.put("requireSignedManifest", preset.requireSignedManifest());
            option.put("enforceOutputContract", preset.enforceOutputContract());
            option.put("failOnUnapprovedPermission", preset.failOnUnapprovedPermission());
            options.add(option);
        }
        return options;
    }

    private static String humanBytes(int bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " B";
    }

    private static String operator() {
        String name = SecurityUtils.getCurrentUsername();
        return name == null ? "system" : name;
    }
}
