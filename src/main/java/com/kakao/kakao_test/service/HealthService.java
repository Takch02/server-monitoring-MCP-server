package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.ServerHealthEvent;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.HealthIngestDto;
import com.kakao.kakao_test.repository.ServerHealthEventRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    private final TargetServerRepository targetServerRepository;
    private final ServerHealthEventRepository healthEventRepository;
    private static final int STALE_SECONDS = 60; // 60초가 지나면 오래된 정보로 인식하여 서버가 죽었다 판단

    @Transactional
    public void saveHealth(String serverName, HealthIngestDto dto, String token) {
        // 1) 서버/토큰 검증
        TargetServer server = targetServerRepository.findByServerName(serverName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown serverName: " + serverName));

        if (!server.getMcpToken().equals(token)) {
            throw new SecurityException("Invalid token");
        }

        // 2) 입력 정규화
        String newStatus = normalizeStatus(dto.status(), dto.httpStatus());
        long ts = (dto.ts() > 0) ? dto.ts() : System.currentTimeMillis();
        String safeMsg = safeMessage(dto.message());

        // 3) 이벤트 저장(이력)
        healthEventRepository.save(ServerHealthEvent.builder()
                .serverName(serverName)
                .ts(ts)
                .status(newStatus)
                .latencyMs(dto.latencyMs())
                .httpStatus(dto.httpStatus())
                .message(safeMsg)
                .build());

        server.updateHealthSnapshot(newStatus, dto.latencyMs(), dto.httpStatus());
        log.info("Server Health Check 완료 (서버 : {})", serverName);
    }

    /**
     * 최근 수신된 Health Check 정보를 반환
     */
    @Transactional(readOnly = true)
    public String getHealthStatusForMcp(String serverName) {
        ServerHealthEvent latestOpt = healthEventRepository.findTop1ByServerNameOrderByTsDesc(serverName).orElseThrow(
                () -> new IllegalArgumentException("Health 데이터가 없습니다. forwarder HEALTH_URL 및 health ingest 설정을 확인하세요. (serverName=" +
                        serverName + ")")
        );


        long now = System.currentTimeMillis();
        long ageMs = now - latestOpt.getTs();
        boolean stale = ageMs > (STALE_SECONDS * 1000L);

        String lastAt = java.time.Instant.ofEpochMilli(latestOpt.getTs()).toString();

        return String.format(
                "Health: %s\nLastCheck: %s (%.1fs ago)\nHTTP: %d\nLatency: %dms\nStale(>%ds): %s",
                latestOpt.getStatus(),
                lastAt,
                ageMs / 1000.0,
                latestOpt.getHttpStatus(),
                latestOpt.getLatencyMs(),
                STALE_SECONDS,
                stale ? "YES" : "NO"
        );
    }

    private String normalizeStatus(String status, int httpStatus) {
        // forwarder가 status를 못 읽는 경우도 있으니 httpStatus로 보정
        if (status == null || status.isBlank()) {
            return (httpStatus == 200) ? "UP" : "DOWN";
        }
        String s = status.trim().toUpperCase();
        return switch (s) {
            case "UP", "DOWN", "UNKNOWN", "OUT_OF_SERVICE" -> s;
            default -> (httpStatus == 200) ? "UP" : "UNKNOWN";
        };
    }

    private String safeMessage(String message) {
        if (message == null) return null;
        String m = message.trim();
        return m.isEmpty() ? null : (m.length() > 500 ? m.substring(0, 500) : m);
    }
}
