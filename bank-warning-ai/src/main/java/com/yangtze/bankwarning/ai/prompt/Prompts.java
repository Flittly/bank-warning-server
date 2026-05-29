package com.yangtze.bankwarning.ai.prompt;

import com.yangtze.bankwarning.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class Prompts {

    private final AiProperties aiProperties;

    public Prompts(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    public String getReportSystem() {
        return aiProperties.getPrompt().getReportSystem();
    }

    public String getQaSystem() {
        return aiProperties.getPrompt().getQaSystem();
    }

    @SuppressWarnings("unchecked")
    public String buildReportPrompt(Map<String, Object> data) {
        String sectionName = String.valueOf(data.getOrDefault("section_name", "未知断面"));
        Object riskValue = data.get("risk_value");
        Object riskLevel = data.get("risk_level");
        Map<String, Object> rawValues = data.get("raw_values") instanceof Map ? 
            (Map<String, Object>) data.get("raw_values") : Collections.emptyMap();
        Map<String, Object> thresholds = data.get("thresholds") instanceof Map ? 
            (Map<String, Object>) data.get("thresholds") : Collections.emptyMap();

        return String.format("""
                请根据以下数据生成河岸崩塌风险评估报告：
                
                【基本信息】
                - 断面名称：%s
                - 综合风险值：%s
                - 风险等级：%s 级（1=低风险，2=中低，3=中高，4=高风险）
                
                【水动力指标】
                - Ky（抗冲刷能力）：%s，阈值：%s
                - PQ（流量强度）：%s，阈值：%s
                - Zd（水位变化）：%s，阈值：%s
                
                【河床演变指标】
                - Sa（冲刷深度）：%s，阈值：%s
                - Ln（冲刷速率）：%s，阈值：%s
                - Zb（高程变化）：%s，阈值：%s
                
                【地质工程指标】
                - Dsed（土层厚度比）：%s，阈值：%s
                - PL（坡度稳定性）：%s
                - LC（承载能力）：%s
                """,
                sectionName, fmt(riskValue), fmt(riskLevel),
                fmt(rawValues.get("Ky")), fmtThreshold(thresholds, "Ky"),
                fmt(rawValues.get("PQ")), fmtThreshold(thresholds, "PQ"),
                fmt(rawValues.get("Zd")), fmtThreshold(thresholds, "Zd"),
                fmt(rawValues.get("Sa")), fmtThreshold(thresholds, "Sa"),
                fmt(rawValues.get("Ln")), fmtThreshold(thresholds, "Ln"),
                fmt(rawValues.get("Zb")), fmtThreshold(thresholds, "Zb"),
                fmt(rawValues.get("Dsed")), fmtThreshold(thresholds, "Dsed"),
                fmtNullable(rawValues.get("PL")), fmtNullable(rawValues.get("LC"))
        );
    }

    public String buildRagPrompt(String question, List<Map<String, Object>> results) {
        String context = results.stream()
                .map(doc -> String.valueOf(doc.getOrDefault("content", "")))
                .collect(Collectors.joining("\n---\n"));

        return String.format("""
                【知识库参考内容】
                %s
                
                【用户问题】
                %s
                
                请基于以上知识库内容回答用户问题。
                """, context, question);
    }

    private String fmt(Object value) {
        if (value == null) return "N/A";
        if (value instanceof Number n) return String.format("%.4f", n.doubleValue());
        return value.toString();
    }

    private String fmtNullable(Object value) {
        return value == null ? "未检测" : fmt(value);
    }

    @SuppressWarnings("unchecked")
    private String fmtThreshold(Map<String, Object> thresholds, String key) {
        if (thresholds == null || !thresholds.containsKey(key)) return "无数据";
        Object t = thresholds.get(key);
        if (t instanceof List<?> list) return list.toString();
        return t.toString();
    }
}
