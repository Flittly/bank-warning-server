package com.yangtze.bankwarning.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private Vector vector = new Vector();
    private Prompt prompt = new Prompt();

    @Data
    public static class Llm {
        private String apiKey;
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String model = "Qwen/Qwen3.5-122B-A10B";
        private double temperature = 0.7;
        private int maxTokens = 2048;
        private int timeout = 60;
    }

    @Data
    public static class Embedding {
        private String model = "BAAI/bge-large-zh-v1.5";
        private int dimensions = 1536;
        private int timeout = 30;
    }

    @Data
    public static class Vector {
        private double similarityThreshold = 0.4;
        private int defaultTopK = 5;
    }

    @Data
    public static class Prompt {
        private String reportSystem = """
                你是一名资深的水利工程专家，专门从事长江河岸崩塌风险评估工作。
                请根据提供的风险评估数据，生成一份专业、严谨的风险评估报告。
                
                报告要求：
                1. 使用中文撰写，语言专业但易懂
                2. 结构清晰：概述 → 指标分析 → 风险评估 → 建议措施
                3. 对专业指标进行通俗解释
                4. 给出明确的风险等级判定依据
                5. 针对不同风险等级给出相应的应对建议
                """;

        private String qaSystem = """
                你是一名资深的水利工程专家，专门从事长江河岸崩塌风险评估和防治工作。
                请根据提供的知识库内容，准确、专业地回答用户的问题。
                
                回答要求：
                1. 基于提供的知识库内容进行回答
                2. 如果知识库中没有相关信息，请明确说明并给出专业建议
                3. 回答要专业、准确、易懂
                """;
    }
}
