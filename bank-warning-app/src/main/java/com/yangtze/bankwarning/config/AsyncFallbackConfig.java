package com.yangtze.bankwarning.config;

import com.yangtze.bankwarning.service.async.TaskDispatchPort;
import com.yangtze.bankwarning.service.async.TaskRunStatePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncFallbackConfig {

    private IllegalStateException disabledException() {
        return new IllegalStateException("Kafka模式未启用，请设置环境变量 KAFKA_ENABLED=true");
    }

    @Bean
    @ConditionalOnMissingBean(TaskDispatchPort.class)
    TaskDispatchPort noopTaskDispatchPort() {
        return task -> {
            throw disabledException();
        };
    }

    @Bean
    @ConditionalOnMissingBean(TaskRunStatePort.class)
    TaskRunStatePort noopTaskRunStatePort() {
        return new TaskRunStatePort() {
            @Override
            public String createRun(String taskId, int expectedCount) {
                throw disabledException();
            }

            @Override
            public void markSectionSuccess(String runId) {
                throw disabledException();
            }

            @Override
            public void markSectionError(String runId, String errorMessage) {
                throw disabledException();
            }
        };
    }
}
