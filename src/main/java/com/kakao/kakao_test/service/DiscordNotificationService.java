package com.kakao.kakao_test.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordNotificationService {

    // 쿨타임 관리
    private final Map<String, Long> lastSentTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 10 * 60 * 1000; // 10분

    public void sendAlert(String webhookUrl, String serverName, String message) {
        // 1. URL 유효성 체크
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        // 2. 쿨타임 체크
        long now = System.currentTimeMillis();
        long last = lastSentTime.getOrDefault(serverName, 0L);
        if (now - last < COOLDOWN_MS) {
            log.info("⏳ 디스코드 알림 스킵 (쿨타임): {}", serverName);
            return;
        }

        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 디스코드 페이로드 생성 (JSON)
            // content 필드에 메시지를 넣으면 됩니다.
            Map<String, String> payload = new HashMap<>();
            String finalMsg = String.format("## 🚨 [%s] 서버 경고\n>>> %s", serverName, message); // 마크다운 적용
            payload.put("content", finalMsg);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);
            
            // POST 요청
            restTemplate.postForEntity(webhookUrl, request, String.class);
            
            log.info("✅ 디스코드 알림 전송 성공: {}", serverName);
            lastSentTime.put(serverName, now);

        } catch (Exception e) {
            log.error("❌ 디스코드 전송 실패: {}", e.getMessage());
        }
    }
}