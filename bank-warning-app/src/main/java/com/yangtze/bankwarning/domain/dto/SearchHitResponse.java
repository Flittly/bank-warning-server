package com.yangtze.bankwarning.domain.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 全局搜索的单条命中。
 *
 * <p>前端拿这个结构同时做三件事：渲染结果行（title/snippet）、高亮（matchedFields +
 * 原始关键词）、跳转（taskId/sectionId/bankId/lng/lat）。
 *
 * <p>之所以把 {@code refKey} 也带上：树剪枝是按 {@code TreeNode.ref} 匹配的
 * （同一个断面在树里会出现两次 —— 「数据资源」下一次、「计算任务」下一次），
 * 用业务主键而不是树节点 id 才能两处都点亮。
 */
public class SearchHitResponse {

    /** task / bank / section / dem / note / report */
    private String kind;
    /** 业务主键：task_id / bank_id / section_id / dem_id */
    private String id;
    private String title;
    private String snippet;
    private String taskId;
    private String sectionId;
    private String bankId;
    private String demKey;
    /** title / snippet 里实际命中的字段，决定前端在哪儿画高亮 */
    private List<String> matchedFields;
    private double score;
    private Double lng;
    private Double lat;
    private LocalDateTime updatedAt;
    /** 同名结果的总条数（section_name 跨任务高度重复，>1 时前端显示「还有 N 条」） */
    private int duplicateCount;

    /** 给树剪枝用：{@code ref.entity -> ref.key} 的映射 */
    public Map<String, String> getRef() {
        String entity = switch (kind == null ? "" : kind) {
            case "task" -> "task";
            case "bank" -> "bank";
            case "section", "note" -> "section";
            case "dem" -> "dem";
            default -> null;
        };
        if (entity == null) {
            return Map.of();
        }
        String key = switch (entity) {
            case "task" -> taskId;
            case "bank" -> bankId;
            case "section" -> sectionId;
            default -> demKey;
        };
        return key == null ? Map.of() : Map.of("entity", entity, "key", key);
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSectionId() {
        return sectionId;
    }

    public void setSectionId(String sectionId) {
        this.sectionId = sectionId;
    }

    public String getBankId() {
        return bankId;
    }

    public void setBankId(String bankId) {
        this.bankId = bankId;
    }

    public String getDemKey() {
        return demKey;
    }

    public void setDemKey(String demKey) {
        this.demKey = demKey;
    }

    public List<String> getMatchedFields() {
        return matchedFields;
    }

    public void setMatchedFields(List<String> matchedFields) {
        this.matchedFields = matchedFields;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(int duplicateCount) {
        this.duplicateCount = duplicateCount;
    }
}
