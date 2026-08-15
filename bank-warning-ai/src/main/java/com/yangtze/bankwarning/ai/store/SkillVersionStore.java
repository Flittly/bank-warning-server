package com.yangtze.bankwarning.ai.store;

import java.util.List;
import java.util.Optional;

/**
 * Skill 版本存储（档位二 · 本地多版本共存）。
 *
 * 记录消费侧已下载/已物化的 skill 版本及其状态：
 *   - ACTIVE      当前生效版本（执行入口按它解析）；
 *   - RETIRED     历史版本，保留目录但不参与执行；
 *   - QUARANTINED 被隔离的版本，执行前裁决会直接拒绝。
 *
 * 一个 skill 同一时刻只有一个 ACTIVE 版本；下载新版本或手动激活时，
 * 旧的 ACTIVE 自动降级为 RETIRED。Nacos 管发布侧有哪些版本，
 * 这张表管消费侧“哪个版本在用、能不能用”。
 */
public interface SkillVersionStore {

    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_RETIRED = "RETIRED";
    String STATUS_QUARANTINED = "QUARANTINED";

    /** 查询某 skill 当前生效版本（可能为空，表示尚未记录版本信息） */
    Optional<SkillVersion> findActive(String skillName);

    /** 按 skill@version 查询 */
    Optional<SkillVersion> findByVersion(String skillName, String version);

    /** 某 skill 的全部已记录版本（按激活时间倒序） */
    List<SkillVersion> listVersions(String skillName);

    /** 全部 skill 的版本记录（按最近更新倒序） */
    List<SkillVersion> listAll();

    /**
     * 下载/物化后注册一个版本并设为 ACTIVE（旧 ACTIVE 自动降级 RETIRED）。
     * 已存在同版本时更新来源与状态，保持幂等。
     *
     * @return 注册后的版本记录
     */
    SkillVersion registerOrActivate(String skillName, String version, String source, String updatedBy);

    /** 手动激活某个已存在版本，返回是否成功（版本不存在返回 false） */
    boolean activate(String skillName, String version, String updatedBy);

    /** 设置版本状态（QUARANTINED / RETIRED 等）；置回 ACTIVE 请走 activate */
    boolean setStatus(String skillName, String version, String status, String updatedBy);

    /** 删除某版本记录，返回是否删除成功 */
    boolean delete(String skillName, String version);

    /** 一条版本记录 */
    record SkillVersion(Long id, String skillName, String version, String source, String status,
                        String downloadedAt, String activatedAt, String updatedBy, String updatedAt) {
    }
}
