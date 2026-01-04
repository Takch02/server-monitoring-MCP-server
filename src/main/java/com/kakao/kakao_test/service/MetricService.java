package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.MetricIngestDto;
import com.kakao.kakao_test.dto.ServerMetricsDto;
import com.kakao.kakao_test.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricService {

    // 1. 최신 상태 저장소 (Key: 서버이름) -> "지금 CPU 몇퍼야?" 용도
    private final Map<String, MetricIngestDto> latestMetrics = new ConcurrentHashMap<>();

    // 2. 히스토리 저장소 (Key: 서버이름, Value: 최근 60개 메트릭) -> "아까 스파이크 튀었어?" 용도
    // 메모리 절약을 위해 최근 10분(10초 간격 * 60개) 정도만 유지
    private final Map<String, Deque<MetricIngestDto>> metricHistory = new ConcurrentHashMap<>();

    private static final int HISTORY_LIMIT = 60; // 최근 60개 데이터 보관
    private final McpService mcpService; // DB 대신 이용

    // --- [1] 데이터 수집 (Ingest) ---
    public void saveMetric(String serverName, MetricIngestDto dto, String token) {
        log.info("Metric 저장 실행, server : {}", serverName);
        verifiedToken(serverName, token);
        // 최신값 갱신
        latestMetrics.put(serverName, dto);

        // 히스토리 저장 (Ring Buffer 처럼 사용)
        metricHistory.computeIfAbsent(serverName, k -> new ArrayDeque<>())
                .addLast(dto);

        // 꽉 차면 오래된 것 버리기
        Deque<MetricIngestDto> history = metricHistory.get(serverName);
        if (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private void verifiedToken(String serverName, String token) {
        TargetServer server =  mcpService.getServerOrThrow(serverName);
        if (!server.getIngestToken().equals(token)) {
            throw new UnauthorizedException("토큰이 유효하지 않습니다.");
        }
    }

    // --- [2] LLM용 조회 (Tools) ---

    // (A) 현재 상태 조회 (기존 getSystemMetrics 대체)
    public ServerMetricsDto getCurrentMetrics(String serverName) {
        MetricIngestDto latest = latestMetrics.get(serverName);

        if (latest == null) {
            log.info("저장된 Metric이 없습니다.");
            return new ServerMetricsDto(0.0, 0.0, 0.0, false); // 데이터 없음
        }

        MetricIngestDto.MetricData data = latest.getData();
        return new ServerMetricsDto(
                data.getCpuUsage() * 100.0,     // 0.5 -> 50.0%
                data.getMemoryUsed() / 1024 / 1024, // Byte -> MB
                data.getMemoryMax() / 1024 / 1024,  // Byte -> MB
                true
        );
    }

    // (B) [중요] 최근 트렌드 분석 (LLM이 장애 원인 찾을 때 사용)
    public String getMetricTrend(String serverName) {
        Deque<MetricIngestDto> history = metricHistory.get(serverName);
        if (history == null || history.isEmpty()) return "데이터 없음";

        // CPU가 80% 넘었던 순간이 있는지 찾기
        long highCpuCount = history.stream()
                .filter(m -> m.getData().getCpuUsage() > 0.8)
                .count();

        if (highCpuCount > 0) {
            return String.format("⚠️ 최근 10분 동안 CPU 사용률이 80%%를 초과한 구간이 %d번 있었습니다. (최대 %.1f%%)",
                    highCpuCount,
                    history.stream().mapToDouble(m -> m.getData().getCpuUsage()).max().orElse(0) * 100
            );
        }
        return "✅ 시스템 리소스 상태는 안정적입니다.";
    }
}
