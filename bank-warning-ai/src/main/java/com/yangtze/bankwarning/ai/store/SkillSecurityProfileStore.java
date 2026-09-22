package com.yangtze.bankwarning.ai.store;

import com.yangtze.bankwarning.ai.security.SkillSecuritySettings;

import java.util.Optional;

/**
 * Skill 安全档位存储（阶段三 · 安全档位）。
 *
 * <p>全局只有一行（{@code id = 1}）：档位是全局策略，不做多租户/多 skill 维度。
 * 每个开关都落成独立列而不是一个 JSON blob，
 * 便于 DBA 用一条 {@code SELECT} 看到完整策略，也让「档位被改成什么」这件事可被 SQL 审计。
 */
public interface SkillSecurityProfileStore {

    /** 读取当前生效档位；尚未落库时返回 empty（由服务层用预设播种） */
    Optional<SkillSecuritySettings> load();

    /** 覆盖保存档位（含逐项微调与熔断状态） */
    void save(SkillSecuritySettings settings, String actor);
}
