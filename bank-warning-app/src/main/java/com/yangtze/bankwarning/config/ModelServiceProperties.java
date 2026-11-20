package com.yangtze.bankwarning.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.model-service")
public class ModelServiceProperties {

    private String baseUrl;
    private String defaultModelApi;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(600);

    /**
     * 等待模型 case 完成时的状态轮询间隔（毫秒）。
     *
     * <p>默认 200ms，对应 {@code app.model-service.poll-interval-ms} /
     * 环境变量 {@code APP_MODEL_POLL_INTERVAL_MS}。历史 case 实测「提交→完成」
     * p50≈1.07s、p90≈1.68s：原来 1s 粒度下平均要白等约 0.5s，而每断面有
     * risk-level 与 section-view 两次等待。缓存命中时第一次查询即 completed，
     * 不走这里的 sleep，所以调短不会给缓存路径增加负担。
     *
     * <p>⚠ 这个默认值必须写在代码里：{@code application.yml} 被 .gitignore 排除，
     * 新克隆 / 打 jar 的场景拿不到 yml 里的显式配置，只能靠这里兜底。
     * 实际生效值还会经过 {@code ModelGatewayService.resolvePollIntervalMillis}
     * 钳制到 [50, 2000] ms，防止配 0 把状态接口打爆。
     */
    private long pollIntervalMs = 200L;

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultModelApi() {
        return defaultModelApi;
    }

    public void setDefaultModelApi(String defaultModelApi) {
        this.defaultModelApi = defaultModelApi;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
