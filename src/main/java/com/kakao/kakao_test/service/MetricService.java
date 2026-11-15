package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.DiagnosticProperties;
import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.MetricIngestDto;
import com.kakao.kakao_test.exception.UnauthorizedException;
import com.kakao.kakao_test.repository.ServerMetricRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetricService {

    private final TargetServerRepository targetServerRepository;
    private final ServerMetricRepository serverMetricRepository;
    private final DiscordNotificationService discordNotificationService;
    private final ServerHeartbeatService serverHeartbeatService;
    private final Clock clock;
    private final DiagnosticProperties diagnosticProperties;

    /**
     * [1] 데이터 수집 (Ingest)
     * - DB 저장
     * - 80% 초과 시 카카오 알림
     */
    @Transactional
    public void saveMetric(String serverName, MetricIngestDto dto, String mcpToken, String discordWebhookUrl) {

        // 1. 서버 조회 및 토큰 검증
        TargetServer server = targetServerRepository.getByServerName(serverName);
        verifyToken(server, mcpToken);

        // 2. HeartBeat 갱신 (다른 트렌젝션에서 처리)
        serverHeartbeatService.updateHeartbeatQuickly(server.getId());
        MetricIngestDto.MetricData data = dto.getData();

        // 3. 단위 변환 및 Entity 생성 (DB에 맞게 변환)
        Double cpuPercent = data.getCpuUsage() * 100.0;
        Double memUsedMb = data.getMemoryUsed() / 1024.0 / 1024.0;
        Double memMaxMb = data.getMemoryMax() / 1024.0 / 1024.0;

        ServerMetric metric = ServerMetric.createMetric(dto.getTs(), server, cpuPercent, memUsedMb, memMaxMb);

        // 4. DB 저장
        serverMetricRepository.save(metric);

        // 5. 위험 감지 및 알림 (80% 초과 시)
        // (Memory Percent 계산)
        double memPercent = (memMaxMb > 0) ? (memUsedMb / memMaxMb) * 100.0 : 0.0;

        if (cpuPercent > 80.0 || memPercent > 80.0) {
            String alertMsg = String.format(
                    "리소스 사용량이 높습니다!\n🔥 CPU: %.1f%%\n💾 JVM 메모리: %.1f%% (%.0fMB / %.0fMB)",
                    cpuPercent, memPercent, memUsedMb, memMaxMb
            );

            // DTO에 담겨온 사용자 토큰으로 발송
            discordNotificationService.sendErrorAlert(discordWebhookUrl, serverName, alertMsg);
            log.info("Metric 저장 (서버: {})ㄴ", serverName);
        }
    }



    /** 관측 기간과 최신성을 따로 확인해 과거의 정상 값을 현재 판정에 쓰지 않는다. */
    public String getMetricTrend(String serverName) {
        TargetServer server = targetServerRepository.getByServerName(serverName);
        // 기존 capturedAt 저장 시 사용한 타임존과 맞춰 비교한다.
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        LocalDateTime from = now.minusMinutes(diagnosticProperties.getMetricWindowMinutes());
        List<ServerMetric> history = serverMetricRepository
                .findByServerAndCapturedAtBetweenOrderByCapturedAtDesc(server, from, now);
        String window = "관측 범위: " + from + " ~ " + now;

        if (history.isEmpty()) {
            String lastSample = serverMetricRepository.findTopByServerOrderByCapturedAtDesc(server)
                    .map(metric -> String.valueOf(metric.getCapturedAt())).orElse("없음");
            return "자원 상태: UNKNOWN — 조회 기간 내 지표가 없어 현재 상태를 확인할 수 없습니다.\n"
                    + window + "\n마지막 관측 시각: " + lastSample;
        }

        LocalDateTime latest = history.getFirst().getCapturedAt();
        String evidence = window + "\n마지막 관측 시각: " + latest + " / 관측 " + history.size() + "건";
        if (latest == null || latest.isAfter(now)
                || Duration.between(latest, now).compareTo(Duration.ofSeconds(diagnosticProperties.getStaleSeconds())) > 0) {
            return "자원 상태: UNKNOWN — 지표 시각이 유효하지 않거나 오래되어 현재 상태를 확인할 수 없습니다.\n" + evidence;
        }
        if (history.stream().anyMatch(metric -> !validMetric(metric))) {
            return "자원 상태: UNKNOWN — CPU 또는 JVM 메모리 값이 유효하지 않아 판정할 수 없습니다.\n" + evidence;
        }

        double avgCpu = history.stream().mapToDouble(ServerMetric::getCpuUsage).average().orElseThrow();
        double avgMem = history.stream().mapToDouble(this::memoryPercent).average().orElseThrow();
        long highCpuCount = history.stream().filter(metric -> metric.getCpuUsage() > 80.0).count();
        long highMemCount = history.stream().filter(metric -> memoryPercent(metric) > 80.0).count();
        String stats = String.format("\n평균 CPU: %.1f%% / 평균 JVM 메모리: %.1f%%", avgCpu, avgMem);
        if (highCpuCount > 0 || highMemCount > 0) {
            double maxCpu = history.stream().mapToDouble(ServerMetric::getCpuUsage).max().orElseThrow();
            return String.format("자원 상태: WARNING — 관측 범위 내 임계값 초과\n%s%s\n"
                    + "CPU 80%% 초과: %d회 (최대 %.1f%%)\nJVM 메모리 80%% 초과: %d회",
                    evidence, stats, highCpuCount, maxCpu, highMemCount);
        }
        return "자원 상태: NORMAL — 관측된 지표에서 임계값 초과가 없습니다.\n" + evidence + stats
                + "\n자원 지표만으로 서버 전체의 정상 여부를 보장하지 않습니다.";
    }

    private boolean validMetric(ServerMetric metric) {
        return finiteNonNegative(metric.getCpuUsage()) && metric.getCpuUsage() <= 100.0
                && finiteNonNegative(metric.getMemoryUsedMb())
                && metric.getMemoryMaxMb() != null && Double.isFinite(metric.getMemoryMaxMb())
                && metric.getMemoryMaxMb() > 0 && metric.getMemoryUsedMb() <= metric.getMemoryMaxMb();
    }

    private boolean finiteNonNegative(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }

    private double memoryPercent(ServerMetric metric) {
        return metric.getMemoryUsedMb() / metric.getMemoryMaxMb() * 100.0;
    }

    // --- Private Helpers ---

    private void verifyToken(TargetServer server, String token) {
        if (!server.getMcpToken().equals(token)) {
            throw new UnauthorizedException("토큰이 유효하지 않습니다.");
        }
    }
}