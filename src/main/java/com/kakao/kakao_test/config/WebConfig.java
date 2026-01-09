package com.kakao.kakao_test.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")  // 모든 곳에서 접속 허용 (보안상 취약하지만 테스트엔 필수)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowCredentials(false); // *를 쓸 땐 false여야 함
    }
}