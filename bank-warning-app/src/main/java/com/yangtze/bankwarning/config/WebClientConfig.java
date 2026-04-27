package com.yangtze.bankwarning.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    WebClient modelWebClient(ModelServiceProperties properties) {
        return WebClient.builder().baseUrl(properties.getBaseUrl()).build();
    }
}
