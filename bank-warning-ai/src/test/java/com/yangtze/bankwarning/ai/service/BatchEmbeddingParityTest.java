package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.EmbeddingBatchProperties;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量嵌入与单条嵌入的<b>对拍</b>：把「批量到底改变了什么」量化出来。
 *
 * <p><b>2026-11-17 首次实测结论（text-embedding-v4，4 段中文语料）</b>：
 * <pre>
 *   同一路径重复调用：      差 0.0e+00            ← 服务端完全确定，排除随机抖动
 *   embed() vs texts[1]：   差 0.0e+00            ← 只装 1 条时，两条路径逐位相同
 *   texts[1] vs texts[4]：  差 1.5e-4 ~ 4.8e-4    ← 余弦 0.9999987
 * </pre>
 * 这个分解把责任定死了：差异<b>不出在「走哪个 SDK 方法」，而出在「同批有没有邻居」</b>——
 * batch&gt;1 时服务端走批内计算路径，浮点累加顺序变了，于是每条都差 1e-4 级。
 *
 * <p>由此得到两档可用配置（都是改 yml，不动代码）：
 * <ul>
 *   <li>{@code batch-size=1} ⇒ 与改造前逐位一致，只是把串行换成 4 路并发（耗时约 1/4）；</li>
 *   <li>{@code batch-size=10} ⇒ 请求数 100→10，接受 1e-4 级数值差与 0.9999987 的余弦。</li>
 * </ul>
 *
 * <p>哪一档都不能「凭感觉定」：这个测试的作用是<b>把差异摆到明面上</b>，让人在有数据
 * 的情况下选，而不是上线后靠「检索好像还行」去猜。
 *
 * <p>默认不发真实请求（要 {@code -Drag.parity=true} 才跑），所以常规 {@code mvn test}
 * 不消耗额度。要真跑：
 * <pre>
 *   mvn -pl bank-warning-ai -am test -Dtest=BatchEmbeddingParityTest \
 *       -Drag.parity=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>通过标准：余弦 ≥ 0.9999。逐维差不再作为门槛——实测已证明 batch&gt;1 时它必不为 0，
 * 拿 1e-6 当标准只会得到一个永远红的测试，而不是一个更安全的上线。
 */
@DisplayName("对拍：量化批量嵌入相对单条嵌入的数值偏差")
class BatchEmbeddingParityTest {

    private static final String MODEL = "text-embedding-v4";
    private static final int DIMENSIONS = 1024;

    /** 用项目自己的语料风格，避免「英文短句对拍通过、中文长段落不一致」。 */
    private static final List<String> SAMPLES = List.of(
            "崩岸风险等级判定以岸坡坡度、土体抗剪强度、近岸水深和护岸工程完好率为主要指标，"
                    + "各指标按实测值分档赋分后加权求和，得到综合风险值 R。",
            "当综合风险值 R 大于等于 0.75 时判为高风险，0.5 至 0.75 为较高风险，"
                    + "0.25 至 0.5 为中风险，小于 0.25 为低风险；等级划分不随任务调整。",
            "断面布设间距原则上不超过 200 米，岸线形态突变处、已有崩岸段和护岸结构衔接处应加密，"
                    + "加密断面的里程以相邻基本断面的中点为边界。",
            "监测数据入库前须完成坐标系转换：原始数据为 CGCS2000 地理坐标，"
                    + "计算前统一投影到 3 度带高斯克吕格，中央经线 120 度。"
    );

    @Test
    @DisplayName("4 段语料：分解出「接口差异」与「同批邻居差异」，余弦 ≥ 0.9999")
    void batchMatchesSingleEmbedding() {
        // 默认不发真实请求：常规 mvn test 一次 API 额度都不该花。
        // 要真跑：mvn -pl bank-warning-ai test -Dtest=BatchEmbeddingParityTest -Drag.parity=true
        Assumptions.assumeTrue(Boolean.getBoolean("rag.parity"),
                "未传 -Drag.parity=true，跳过对拍（避免常规测试消耗额度）");

        String apiKey = resolveApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未找到 DashScope API Key（环境变量 DASHSCOPE_API_KEY 或 application-local.yml），跳对拍");

        // 路径 A：生产检索用的单条封装
        var single = DashScopeTextEmbedding.builder()
                .apiKey(apiKey)
                .modelName(MODEL)
                .dimensions(DIMENSIONS)
                .build();

        // 路径 B：入库用的批量客户端
        BatchEmbeddingClient batch = BatchEmbeddingClient.dashscope(
                apiKey, MODEL, DIMENSIONS, null, EmbeddingBatchProperties.enabledWithDefaults());

        List<double[]> batchVectors = batch.embedBatch(SAMPLES);
        assertEquals(SAMPLES.size(), batchVectors.size());

        List<String> report = new ArrayList<>();
        double worstSolo = 0d;
        double worstNeighbor = 0d;
        double worstCross = 0d;
        double worstCosine = 1d;
        for (int i = 0; i < SAMPLES.size(); i++) {
            TextBlock block = TextBlock.builder().text(SAMPLES.get(i)).build();
            double[] one = single.embed(block).block();
            assertNotNull(one, "单条路径返回空向量，无法对拍");
            // 基线：同一段文本在「同一条路径」上重复一次。
            // 如果这里就差 1e-4，说明是服务端本身非确定，不是批量引入的偏差。
            double[] oneAgain = single.embed(block).block();

            double[] many = batchVectors.get(i);
            assertEquals(one.length, many.length, "维度不一致，批量路径的 dimension 参数传错了");

            // 组 B：同样走 texts 接口，但只装 1 条 —— 隔离出「接口差异」有多大
            double[] solo = batch.embedBatch(List.of(SAMPLES.get(i))).get(0);
            // 组 C：同一条文本，1 条一批 vs 4 条一批 —— 隔离出「同批邻居」的影响
            double baseline = maxDiff(one, oneAgain);
            double soloDiff = maxDiff(one, solo);
            double neighborDiff = maxDiff(solo, many);
            double cross = maxDiff(one, many);
            double cosine = cosine(one, many);

            worstSolo = Math.max(worstSolo, soloDiff);
            worstNeighbor = Math.max(worstNeighbor, neighborDiff);
            worstCross = Math.max(worstCross, cross);
            worstCosine = Math.min(worstCosine, cosine);

            report.add(String.format(
                    "段%d: 自比=%.1e | 单条vs texts[1]=%.1e | texts[1] vs texts[4]=%.1e | cosine=%.7f",
                    i, baseline, soloDiff, neighborDiff, cosine));
        }

        System.out.println("[parity] 批量 vs 单条");
        report.forEach(line -> System.out.println("[parity]   " + line));
        System.out.printf("[parity] 最差: texts[1]偏差=%.3e, 邻居偏差=%.3e, 交叉偏差=%.3e, cosine=%.7f%n",
                worstSolo, worstNeighbor, worstCross, worstCosine);

        // 判定：余弦几乎为 1 就放行；同时把两个分解项打出来，
        // 便于以后判断「偏差变大了」是接口变了还是模型换了。
        assertTrue(worstCosine >= 0.9999,
                "余弦相似度掉到 0.9999 以下，说明两边算法路径已不同。\n对拍明细：" + report);
    }

    private static double maxDiff(double[] a, double[] b) {
        double max = 0d;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 依次找：环境变量 → 本机 {@code application-local.yml}。
     * 都取不到就返回 null，由 {@code Assumptions} 跳过整个测试。
     */
    private static String resolveApiKey() {
        String env = System.getenv("DASHSCOPE_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return readFromLocalYml();
    }

    private static String readFromLocalYml() {
        // 测试工作目录是 bank-warning-ai，往回找 app 模块的本地配置
        Path[] candidates = {
                Path.of("..", "bank-warning-app", "src", "main", "resources", "application-local.yml"),
                Path.of("bank-warning-app", "src", "main", "resources", "application-local.yml")
        };
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                // 必须只取 dashscope 段下的 api-key：本文件里 agentscope.openai.api-key
                // （DeepSeek）排在前面，取第一个会把对话模型的 key 发给 DashScope ⇒ 401。
                boolean inDashscope = false;
                for (String line : Files.readAllLines(p)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("dashscope:")) {
                        inDashscope = true;
                        continue;
                    }
                    if (!inDashscope) {
                        continue;
                    }
                    if (trimmed.startsWith("api-key:")) {
                        String value = trimmed.substring("api-key:".length()).trim();
                        if (!value.isEmpty() && !value.startsWith("${")) {
                            return value;
                        }
                        return null;
                    }
                    // dashscope 段结束：遇到下一个顶格键
                    if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                            && !trimmed.startsWith("#")) {
                        break;
                    }
                }
            } catch (IOException ignored) {
                // 读不到就当没有，交给 Assumptions 跳过
            }
        }
        return null;
    }
}
