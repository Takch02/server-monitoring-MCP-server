package com.kakao.kakao_test.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordNotificationService {

    // 쿨타임 관리
    private final Map<String, Long> lastSentTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 10 * 60 * 1000; // 10분

    private final RestClient restClient = RestClient.create();

    private final RestClient logClient = logDeliveryClient();

    private static RestClient logDeliveryClient() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }

    // 워커는 2xx 응답 이후에만 발송 성공을 기록한다.
    public void sendLogDelivery(String url, String message) {
        var response = logClient.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("content", message, "allowed_mentions", Map.of("parse", List.of())))
                .retrieve().toBodilessEntity();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Discord HTTP " + response.getStatusCode().value());
        }
    }

    /**
     * [1] 시스템 에러 알림용 (쿨타임 적용 O)
     * - 짧은 에러 로그 전송 시 사용
     * - 10분 내 재발송 방지
     */
    @Async
    public void sendErrorAlert(String webhookUrl, String serverName, String message) {
        if (checkCooldown(serverName)) {
            log.info("⏳ 디스코드 알림 스킵 (쿨타임): {}", serverName);
            return;
        }

        // 알림 전용 포맷
        String formattedMsg = String.format("## 🚨 [%s] 서버 경고\n>>> %s", serverName, message);
        sendToDiscord(webhookUrl, formattedMsg);

        // 전송 성공 시 쿨타임 갱신
        lastSentTime.put(serverName, System.currentTimeMillis());
    }

    /**
     * [2] AI 리포트 / 일반 메시지용 (쿨타임 적용 X)
     * - 사용자가 요청한 AI 진단 결과 전송 시 사용
     * - 즉시 전송
     */
    public void sendAiReport(String webhookUrl, String message) {
        // AI 리포트는 별도 포맷팅 없이 그대로 보내거나, 필요한 헤더를 여기서 붙임
        sendToDiscord(webhookUrl, message);
    }

    /**
     * [내부 메서드] 실제 전송 로직
     * - 2000자 제한 처리 (자동 분할 전송)
     */
    private void sendToDiscord(String webhookUrl, String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        try {
            // 디스코드 2000자 제한 방어 로직
            if (message.length() > 2000) {
                sendSplitMessages(webhookUrl, message);
            } else {
                sendRequest(webhookUrl, message);
            }
        } catch (Exception e) {
            log.error("❌ 디스코드 전송 실패: {}", e.getMessage());
        }
    }

    // 메시지가 길 경우 1900자 단위로 잘라서 여러 번 보냄
    private void sendSplitMessages(String webhookUrl, String message) {
        int chunkSize = 1900; // 여유 있게 1900자
        int length = message.length();

        for (int i = 0; i < length; i += chunkSize) {
            int end = Math.min(length, i + chunkSize);
            String chunk = message.substring(i, end);
            sendRequest(webhookUrl, chunk);

            // 순서 보장을 위한 미세한 딜레이
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        }
    }

    private void sendRequest(String webhookUrl, String content) {
        Map<String, String> payload = Map.of("content", content);

        restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.info("✅ 디스코드 메시지 발송 완료");
    }

    private boolean checkCooldown(String serverName) {
        long now = System.currentTimeMillis();
        long last = lastSentTime.getOrDefault(serverName, 0L);
        return (now - last) < COOLDOWN_MS;
    }
}