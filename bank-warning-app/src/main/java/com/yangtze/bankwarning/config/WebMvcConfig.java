package com.yangtze.bankwarning.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.ai.visualization.output-dir:visualization/output}")
    private String vizOutputDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String path = new File(vizOutputDir).getAbsolutePath();
        if (!path.endsWith(File.separator)) {
            path += File.separator;
        }
        registry.addResourceHandler("/v0/bank/ai/viz-output/**")
                .addResourceLocations("file:" + path)
                .setCachePeriod(3600);
    }
}
