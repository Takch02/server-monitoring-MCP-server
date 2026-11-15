package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.DiagnosticProperties;
import com.kakao.kakao_test.domain.ServerHealthEvent;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.HealthIngestDto;
import com.kakao.kakao_test.repository.ServerHealthEventRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    private final TargetServerRepository targetServerRepository;
    private final ServerHealthEventRepository healthEventRepository;
    private final ServerHeartbeatService serverHeartbeatService;
    private final Clock clock;
    private final DiagnosticProperties diagnosticProperties;

    @Transactional
    public void saveHealth(String serverName, HealthIngestDto dto, String token) {
        // 1) 서버/토큰 검증
        TargetServer server = targetServerRepository.findByServerName(serverName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown serverName: " + serverName));

        // 하트비트 갱신 (x-lock 을 얻어야 하므로 다른 트렌젝션으로 빼며 데드락을 회피)
        serverHeartbeatService.updateHeartbeatQuickly(server.getId());

        if (!server.getMcpToken().equals(token)) {
            throw new SecurityException("Invalid token");
        }

        // 2) 입력 정규화
        String newStatus = normalizeStatus(dto.status(), dto.httpStatus());
        long ts = (dto.ts() > 0) ? dto.ts() : clock.millis();
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
        ServerHealthEvent latest = healthEventRepository.findTop1ByServerNameOrderByTsDesc(serverName)
                .orElseThrow(() -> new IllegalArgumentException("Health 데이터가 없습니다. forwarder HEALTH_URL 및 health ingest 설정을 확인하세요. (serverName=" + serverName + ")"));
        return formatHealthStatus(latest);
    }

    @Transactional(readOnly = true)
    public String getHealthStatusForDiagnosis(String serverName) {
        return healthEventRepository.findTop1ByServerNameOrderByTsDesc(serverName)
                .map(this::formatHealthStatus)
                .orElse("Health: UNKNOWN — Health 데이터가 없어 현재 응답 상태를 확인할 수 없습니다.");
    }

    private String formatHealthStatus(ServerHealthEvent latestOpt) {
        long now = clock.millis();
        long ageMs = now - latestOpt.getTs();
        boolean stale = Duration.ofMillis(ageMs).compareTo(Duration.ofSeconds(diagnosticProperties.getStaleSeconds())) > 0;

        String lastAt = java.time.Instant.ofEpochMilli(latestOpt.getTs()).toString();

        if (ageMs < 0) {
            return "Health: UNKNOWN — 관측 시각이 미래여서 현재 상태를 판정할 수 없습니다.\nLastCheck: " + lastAt;
        }

        if (stale) {
            double ageSeconds = ageMs / 1000.0;
            String ageLabel;
            if (ageSeconds < 3600) {
                ageLabel = String.format("%.0f분", ageSeconds / 60.0);
            } else if (ageSeconds < 86400) {
                ageLabel = String.format("%.1f시간", ageSeconds / 3600.0);
            } else {
                ageLabel = String.format("%.1f일", ageSeconds / 86400.0);
            }
            return String.format(
                    "Health: UNKNOWN (⚠️ 마지막 응답이 %s 전 — 서버가 다운되었거나 forwarder가 중단된 것으로 판단됩니다)\nLastCheck: %s\nLastKnownStatus: %s",
                    ageLabel,
                    lastAt,
                    latestOpt.getStatus()
            );
        }

        return String.format(
                "Health: %s\nLastCheck: %s (%.1fs ago)\nHTTP: %d\nLatency: %dms",
                latestOpt.getStatus(),
                lastAt,
                ageMs / 1000.0,
                latestOpt.getHttpStatus(),
                latestOpt.getLatencyMs()
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
