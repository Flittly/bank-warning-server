package com.yangtze.bankwarning.ai.security;

/**
 * Skill 安全档位（profile）。
 *
 * <p>把散落在阶段一/二/三里的十几个开关收敛成三个「成熟模式」，
 * 使「收紧」变成一次可审计的动作，而不是改 yml + 重启。
 *
 * <p>档位只决定<b>预设值</b>。落库后每一项仍可单独微调（应急场景），
 * 此时 {@code profile} 退化为「最后一次套用的预设」这一标签，
 * 服务层会用 {@link SkillSecuritySettings#matchesPreset()} 判断当前是否已被改偏。
 */
public enum SkillSecurityProfile {

    LOOSE("宽松（开发）",
            "便于本地开发调试：沙箱关闭、危险 import 仅告警、输出契约不强制。"
                    + "路径防逃逸仍然常开，不可关闭。"),

    STANDARD("标准（生产默认）",
            "与系统既有默认值完全一致：进程级沙箱、缺校验清单即拒绝、"
                    + "危险 import 拒绝执行、输出契约强制校验。"),

    STRICT("严格（高敏）",
            "在标准档基础上进一步收紧：沙箱超时压到 30s、输出上限压到 256KB、"
                    + "进程数与 CPU 配额减半、危险 import 黑名单扩充（反序列化 / 动态导入 / 直连网络）、"
                    + "要求校验清单必须带签名。");

    private final String label;
    private final String description;

    SkillSecurityProfile(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 宽容解析：无法识别时回退到 fallback（不抛异常，避免配置写坏导致启动失败） */
    public static SkillSecurityProfile parse(String raw, SkillSecurityProfile fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
