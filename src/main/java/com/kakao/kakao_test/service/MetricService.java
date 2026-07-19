package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.MetricIngestDto;
import com.kakao.kakao_test.dto.ServerMetricsDto;
import com.kakao.kakao_test.exception.UnauthorizedException;
import com.kakao.kakao_test.repository.ServerMetricRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
                    "리소스 사용량이 높습니다!\n🔥 CPU: %.1f%%\n💾 RAM: %.1f%% (%.0fMB / %.0fMB)",
                    cpuPercent, memPercent, memUsedMb, memMaxMb
            );

            // DTO에 담겨온 사용자 토큰으로 발송
            discordNotificationService.sendErrorAlert(discordWebhookUrl, serverName, alertMsg);
            log.info("Metric 저장 (서버: {})ㄴ", serverName);
        }
    }



    /**
     * [2] 현재 상태 조회 (LLM Tools용)
     * - DB에서 가장 최신 메트릭 1개 조회
     */
    public ServerMetricsDto getCurrentMetrics(String serverName) {
        TargetServer server = targetServerRepository.getByServerName(serverName);

        // DB에서 최신값 1개 가져오기
        return serverMetricRepository.findTopByServerOrderByCapturedAtDesc(server)
                .map(m -> new ServerMetricsDto(
                        m.getCpuUsage(),        // 이미 % 단위로 저장됨
                        m.getMemoryUsedMb(),    // 이미 MB 단위
                        m.getMemoryMaxMb(),
                        true
                ))
                .orElseGet(() -> new ServerMetricsDto(0.0, 0.0, 0.0, false));
    }

    /**
     * [3] 최근 트렌드 분석 (LLM Tools용)
     * - 최근 10분(또는 최근 60개) 데이터를 조회하여 분석
     */
    public String getMetricTrend(String serverName) {
        TargetServer server = targetServerRepository.getByServerName(serverName);

        // 최근 60개 데이터 조회 (약 10분치)
        List<ServerMetric> history = serverMetricRepository.findTop50ByServerOrderByCapturedAtDesc(server);

        if (history.isEmpty()) return "데이터 없음";

        // 1. 평균 CPU 사용량 계산
        double avgCpu = history.stream()
                .mapToDouble(ServerMetric::getCpuUsage)
                .average()
                .orElse(0.0);

        // 2. 평균 메모리 사용률 계산 (%)
        double avgMem = history.stream()
                .mapToDouble(m -> ((double) m.getMemoryUsedMb() / m.getMemoryMaxMb()) * 100.0)
                .average()
                .orElse(0.0);

        // CPU가 80% 넘었던 순간이 있는지 카운팅
        long highCpuCount = history.stream()
                .filter(m -> m.getCpuUsage() > 80.0)
                .count();

        // 메모리가 80% 넘었던 순간
        long highMemCount = history.stream()
                .filter(m -> (m.getMemoryUsedMb() / m.getMemoryMaxMb() * 100.0) > 80.0)
                .count();

        // 4. 결과 문자열 포맷팅
        String statsSummary = String.format("\n(평균 CPU: %.1f%% / 평균 RAM: %.1f%%)", avgCpu, avgMem);

        if (highCpuCount > 0 || highMemCount > 0) {
            double maxCpu = history.stream().mapToDouble(ServerMetric::getCpuUsage).max().orElse(0.0);

            return String.format(
                    "⚠️ 최근 10분간 리소스 불안정:%s\n- CPU 80%% 초과: %d회 (최대 %.1f%%)\n- 메모리 80%% 초과: %d회",
                    statsSummary, highCpuCount, maxCpu, highMemCount
            );
        }

        return "✅ 최근 10분간 시스템 리소스 상태는 매우 안정적입니다." + statsSummary;
    }

    // --- Private Helpers ---

    private void verifyToken(TargetServer server, String token) {
        if (!server.getMcpToken().equals(token)) {
            throw new UnauthorizedException("토큰이 유효하지 않습니다.");
        }
    }
}