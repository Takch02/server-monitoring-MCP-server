package com.kakao.kakao_test.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class WebConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // 중요: allowCredentials가 true일 때는 allowedOrigins("*") 대신 Patterns를 써야 함
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("*"); // 모든 출처 허용
        config.addAllowedHeader("*");        // 모든 헤더 허용
        config.addAllowedMethod("*");        // GET, POST, PUT, DELETE, OPTIONS 등 모두 허용

        // 클라이언트(JS)에서 접근할 수 있게 할 헤더 지정
        config.addExposedHeader("Authorization");
        config.addExposedHeader("mcp-session-id");

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}