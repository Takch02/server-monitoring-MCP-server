package com.kakao.kakao_test.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
public class IncidentLinkConfig {
    public record Settings(URI publicOrigin, SecretKeySpec signingKey) {
        public boolean enabled() {
            return publicOrigin != null;
        }
    }

    @Bean
    Settings incidentLinkSettings(@Value("${incidents.public-url}") String publicUrl,
                                  @Value("${incidents.link-key}") String encodedKey) {
        if (publicUrl.isBlank()) return new Settings(null, null);

        URI origin = URI.create(publicUrl);
        String path = origin.getRawPath();
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || (!path.isEmpty() && !"/".equals(path))) {
            throw new IllegalArgumentException("INCIDENT_PUBLIC_URL must be an HTTPS origin");
        }

        byte[] key = Base64.getDecoder().decode(encodedKey);
        if (key.length != 32) {
            throw new IllegalArgumentException("INCIDENT_LINK_KEY must encode 32 bytes");
        }
        return new Settings(origin, new SecretKeySpec(key, "HmacSHA256"));
    }
}
