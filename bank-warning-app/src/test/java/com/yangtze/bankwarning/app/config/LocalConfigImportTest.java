package com.yangtze.bankwarning.app.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「本机专用 key 通过 spring.config.import 生效」这条链路。
 *
 * <p>背景：为了让 API Key 不随源码包外泄，application.yml 里不再写任何默认值，
 * 本机 key 单独放在 application-local.yml（.gitignore + 打包 --exclude 双重排除），
 * 由 application.yml 的 {@code spring.config.import: optional:classpath:...} 导入。
 *
 * <p>这个测试要证的就是「导入真的生效，且优先级高于 application.yml 的空默认值」。
 * 因为该文件在服务器 / CI 上不存在（被排除），本测试会自动跳过，
 * 避免在无 key 的环境里误报失败。
 */
class LocalConfigImportTest {

    @Test
    @DisplayName("application-local.yml 存在时，key 被导入并覆盖 application.yml 的空默认值")
    void localKeyOverridesBlankDefault() {
        Assumptions.assumeTrue(
                new ClassPathResource("application-local.yml").exists(),
                "本机未提供 application-local.yml（服务器/CI 的正常状态），跳过");

        SpringApplication app = new SpringApplication(EmptyConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);

        try (ConfigurableApplicationContext ctx = app.run(
                "--spring.main.lazy-initialization=true",
                "--spring.main.register-shutdown-hook=false")) {
            String key = ctx.getEnvironment().getProperty("agentscope.openai.api-key");
            assertNotNull(key, "key 不应为 null");
            assertTrue(key.startsWith("sk-"), "应取到 application-local.yml 里的真实 key，实际=[" + key + "]");

            String dashscope = ctx.getEnvironment().getProperty("agentscope.dashscope.api-key");
            assertTrue(dashscope != null && dashscope.startsWith("sk-"),
                    "DashScope key 也应生效，实际=[" + dashscope + "]");
        }
    }

    @Configuration
    static class EmptyConfig {
    }
}
