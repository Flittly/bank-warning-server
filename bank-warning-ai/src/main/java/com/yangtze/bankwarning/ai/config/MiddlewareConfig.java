package com.yangtze.bankwarning.ai.config;

import com.yangtze.bankwarning.ai.middleware.ReasoningTraceMiddleware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MiddlewareConfig {

    @Bean
    public ReasoningTraceMiddleware chatTraceMiddleware() {
        return new ReasoningTraceMiddleware();
    }

    @Bean
    public ReasoningTraceMiddleware reportTraceMiddleware() {
        return new ReasoningTraceMiddleware();
    }
}
