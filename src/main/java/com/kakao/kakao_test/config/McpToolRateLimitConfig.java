package com.kakao.kakao_test.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(McpToolRateLimitProperties.class)
public class McpToolRateLimitConfig {

    @Bean
    public Clock mcpToolRateLimitClock() {
        return Clock.systemUTC();
    }
}
