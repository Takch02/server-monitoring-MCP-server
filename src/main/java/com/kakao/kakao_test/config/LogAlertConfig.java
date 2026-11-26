package com.kakao.kakao_test.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
public class LogAlertConfig {
    @Bean
    SecretKeySpec webhookEncryptionKey(@Value("${alerts.webhook-key}") String encodedKey) {
        byte[] bytes = Base64.getDecoder().decode(encodedKey);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("ALERT_WEBHOOK_KEY must encode 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
