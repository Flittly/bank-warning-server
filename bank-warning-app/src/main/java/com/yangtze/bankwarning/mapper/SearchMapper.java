package com.yangtze.bankwarning.mapper;

import com.yangtze.bankwarning.domain.dto.SearchHitResponse;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 全局搜索（跨任务 / 岸段 / 断面 / DEM / 人工备注 / 报告）。
 *
 * <p>一条 SQL 里用 UNION ALL 把六类对象拍平成同构结果集，再统一打分排序。
 * 不做「每类一个接口 + 前端合并」—— 那样分页与全局排序都会失真。
 */
public interface SearchMapper {

    /**
     * @param pattern  ILIKE 模式（含 {@code %}），为 null 时不调用（由 Service 提前短路）
     * @param exact    完整输入的等值匹配目标（权重 3.0）
     * @param prefix   首个词的前缀模式（权重 2.0）
     * @param types    限定对象类型，空表示全部
     * @param riskLevel 只约束断面段；null 表示不限
     * @param taskId    只约束断面 / DEM / 备注段
     * @param userId    null 表示 admin（不过滤）
     */
    List<SearchHitResponse> search(
            @Param("pattern") String pattern,
            @Param("exact") String exact,
            @Param("prefix") String prefix,
            @Param("types") List<String> types,
            @Param("riskLevel") Integer riskLevel,
            @Param("taskId") String taskId,
            @Param("bankId") String bankId,
            @Param("userId") Long userId,
            @Param("limit") int limit);
}
