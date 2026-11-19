package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.config.HybridRagProperties;
import com.yangtze.bankwarning.ai.config.RagMode;
import com.yangtze.bankwarning.ai.config.RagProperties;
import com.yangtze.bankwarning.ai.config.RagThresholdProperties;

/**
 * 阶段 5：按当前档位决定「用哪把尺子、量在哪一层」。
 *
 * <p>整个类是一个纯函数，没有状态、不碰数据库——因为它回答的是<b>口径</b>问题，
 * 口径必须是可单测、可复述的。改造前这件事散落在两处（{@code SqlPermissionVectorSearch}
 * 内部读 {@code props.scoreThreshold}、SDK 路径塞 {@code searchDto.scoreThreshold}），
 * 两处各写一遍，谁也不知道线上到底按哪个数在筛。
 *
 * <p><b>三档三把尺</b>：
 * <table>
 *   <tr><th>档位</th><th>召回底线（向量路）</th><th>最终门槛</th></tr>
 *   <tr><td>dense</td><td>{@code score-threshold}（0.4）</td><td>同左，粗筛即终审</td></tr>
 *   <tr><td>hybrid</td><td>{@code recall-floor}（0.0）</td><td>无（靠 topK 截断）</td></tr>
 *   <tr><td>hybrid+rerank</td><td>{@code recall-floor}（0.0）</td><td>{@code rerank-score-threshold}</td></tr>
 * </table>
 *
 * <p><b>为什么 hybrid 无最终门槛也不担心</b>：返给模型的条数恒等于 topK，底线放到 0
 * 只影响「送给精排的是哪 30 条」，不影响「用户看到几条」。真正会让离题内容混进答案的
 * 是精排门槛，那个值必须用分数分布标定（见 {@code reports/rag-eval-p5-threshold.md}），
 * 不能凭空给。
 */
public final class RagThresholdPolicy {

    /**
     * 一次检索的阈值口径。
     *
     * @param mode        生效档位，写进留痕
     * @param recallFloor 向量路（粗筛）的最低相似度
     * @param finalFloor  返回前的最后一道门槛；0 = 不设门槛
     * @param reason      一句话说明，用于启动自检与排障
     */
    public record Thresholds(String mode, double recallFloor, double finalFloor, String reason) {
    }

    private RagThresholdPolicy() {
    }

    public static Thresholds of(RagProperties props,
                                HybridRagProperties hybrid,
                                boolean rerankActive,
                                double rerankThreshold,
                                RagThresholdProperties thresholdProps) {
        boolean hybridOn = hybrid != null && hybrid.enabled;
        String mode = RagMode.name(hybridOn, rerankActive);

        if (!hybridOn) {
            // 纯向量档：向量路就是终审，0.4 仍然生效。这里刻意不读 recall-floor——
            // 老部署里没配过这个新参数，若让它接管，升级瞬间就会把门槛降到 0。
            // 精排也开时，0.4 仍是召回门槛，最后一层门槛换成精排自己的阈值。
            double floor = props.scoreThreshold;
            double fin = rerankActive ? rerankThreshold : floor;
            String reason = rerankActive
                    ? "纯向量召回 + 精排：向量门槛 " + floor + "，精排门槛 " + fin
                    : "纯向量档：相似度门槛 " + floor + " 同时作为召回与最终门槛";
            return new Thresholds(mode, floor, fin, reason);
        }

        double recall = thresholdProps == null ? 0.0 : thresholdProps.recallFloor;
        double fin = rerankActive ? rerankThreshold : 0.0;
        String reason = rerankActive
                ? "混合+精排：向量路只保召回（底线 " + recall + "），最终门槛由精排 " + fin + " 判定"
                : "混合档：向量路只保召回（底线 " + recall + "），最终按 topK 截断";
        return new Thresholds(mode, recall, fin, reason);
    }
}
