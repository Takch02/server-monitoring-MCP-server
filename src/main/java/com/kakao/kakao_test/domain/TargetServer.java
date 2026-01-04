package com.kakao.kakao_test.domain;

import lombok.Getter;

import java.time.Instant;

@Getter
public class TargetServer {
    private final String name;
    private volatile String url;              // ngrok 등으로 변경 가능하므로 volatile
    private final String healthPath;          // 예: /actuator/health
    private final String ingestToken;         // 로그 수신 인증용(데모)
    private final Instant createdAt;

    public TargetServer(String name, String url, String healthPath, String ingestToken) {
        this.name = name;
        this.url = normalizeBaseUrl(url);
        this.healthPath = (healthPath == null || healthPath.isBlank()) ? "/actuator/health" : healthPath;
        this.ingestToken = ingestToken;
        this.createdAt = Instant.now();
    }

    public void updateUrl(String newUrl) {
        this.url = normalizeBaseUrl(newUrl);
    }

    private String normalizeBaseUrl(String u) {
        if (u == null) return "";
        // 끝 슬래시 제거
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
