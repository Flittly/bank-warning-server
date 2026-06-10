package com.yangtze.bankwarning.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WebClientConfig {

    @Bean
    RestClient modelRestClient(ModelServiceProperties properties) {
        return RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }
}
