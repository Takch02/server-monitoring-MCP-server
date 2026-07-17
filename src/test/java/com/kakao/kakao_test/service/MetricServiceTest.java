package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.MetricIngestDto;
import com.kakao.kakao_test.dto.ServerMetricsDto;
import com.kakao.kakao_test.exception.UnauthorizedException;
import com.kakao.kakao_test.repository.ServerMetricRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class MetricServiceTest {

    @Mock TargetServerRepository targetServerRepository;
    @Mock ServerMetricRepository serverMetricRepository;
    @Mock DiscordNotificationService discordNotificationService;
    @Mock ServerHeartbeatService serverHeartbeatService;

    @InjectMocks MetricService metricService;

    private TargetServer server;

    @BeforeEach
    void setUp() {
        server = TargetServer.builder()
                .serverName("test-server")
                .mcpToken("valid-token")
                .build();
        given(targetServerRepository.getByServerName("test-server")).willReturn(server);
    }

    // ===== saveMetric =====

    @Test
    void 잘못된토큰_예외발생() {
        assertThatThrownBy(() ->
                metricService.saveMetric("test-server", dto(0.5, mb(500), mb(1000)), "wrong-token", null)
        ).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void CPU80초과_디스코드알림발송() {
        metricService.saveMetric("test-server", dto(0.85, mb(500), mb(1000)), "valid-token", "https://discord-webhook");

        then(discordNotificationService).should()
                .sendErrorAlert(eq("https://discord-webhook"), eq("test-server"), anyString());
    }

    @Test
    void 메모리80초과_디스코드알림발송() {
        metricService.saveMetric("test-server", dto(0.3, mb(850), mb(1000)), "valid-token", "https://discord-webhook");

        then(discordNotificationService).should()
                .sendErrorAlert(eq("https://discord-webhook"), eq("test-server"), anyString());
    }

    @Test
    void 정상범위_디스코드알림없음() {
        metricService.saveMetric("test-server", dto(0.5, mb(500), mb(1000)), "valid-token", "https://discord-webhook");

        then(discordNotificationService).shouldHaveNoInteractions();
    }

    @Test
    void 단위변환_올바른저장값() {
        metricService.saveMetric("test-server", dto(0.5, mb(500), mb(1000)), "valid-token", null);

        ArgumentCaptor<ServerMetric> captor = ArgumentCaptor.forClass(ServerMetric.class);
        then(serverMetricRepository).should().save(captor.capture());

        ServerMetric saved = captor.getValue();
        assertThat(saved.getCpuUsage()).isEqualTo(50.0);
        assertThat(saved.getMemoryUsedMb()).isEqualTo(500.0);
        assertThat(saved.getMemoryMaxMb()).isEqualTo(1000.0);
    }

    // ===== getCurrentMetrics =====

    @Test
    void 저장된메트릭없음_기본값반환() {
        given(serverMetricRepository.findTopByServerOrderByCapturedAtDesc(server))
                .willReturn(Optional.empty());

        ServerMetricsDto result = metricService.getCurrentMetrics("test-server");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCpuUsagePercent()).isZero();
    }

    @Test
    void 저장된메트릭있음_최신값반환() {
        ServerMetric metric = metric(75.0, 800.0, 1000.0);
        given(serverMetricRepository.findTopByServerOrderByCapturedAtDesc(server))
                .willReturn(Optional.of(metric));

        ServerMetricsDto result = metricService.getCurrentMetrics("test-server");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCpuUsagePercent()).isEqualTo(75.0);
        assertThat(result.getJvmMemoryUsedMb()).isEqualTo(800.0);
        assertThat(result.getJvmMemoryMaxMb()).isEqualTo(1000.0);
    }

    // ===== getMetricTrend =====

    @Test
    void 히스토리없음_데이터없음반환() {
        given(serverMetricRepository.findTop50ByServerOrderByCapturedAtDesc(server))
                .willReturn(Collections.emptyList());

        assertThat(metricService.getMetricTrend("test-server")).isEqualTo("데이터 없음");
    }

    @Test
    void 정상범위_안정적반환() {
        given(serverMetricRepository.findTop50ByServerOrderByCapturedAtDesc(server))
                .willReturn(List.of(
                        metric(30.0, 500.0, 1000.0),
                        metric(40.0, 400.0, 1000.0)
                ));

        assertThat(metricService.getMetricTrend("test-server")).contains("✅");
    }

    @Test
    void CPU80초과_불안정경고반환() {
        given(serverMetricRepository.findTop50ByServerOrderByCapturedAtDesc(server))
                .willReturn(List.of(
                        metric(90.0, 500.0, 1000.0),
                        metric(30.0, 400.0, 1000.0)
                ));

        String result = metricService.getMetricTrend("test-server");
        assertThat(result).contains("⚠️");
        assertThat(result).contains("CPU 80%");
    }

    @Test
    void 메모리90초과_불안정경고반환() {
        given(serverMetricRepository.findTop50ByServerOrderByCapturedAtDesc(server))
                .willReturn(List.of(
                        metric(30.0, 950.0, 1000.0),
                        metric(30.0, 400.0, 1000.0)
                ));

        String result = metricService.getMetricTrend("test-server");
        assertThat(result).contains("⚠️");
        assertThat(result).contains("메모리 90%");
    }

    // ===== helpers =====

    /** bytes 단위로 변환 (saveMetric 입력값은 bytes) */
    private double mb(double mb) {
        return mb * 1024.0 * 1024.0;
    }

    private MetricIngestDto dto(double cpuUsage, double memUsedBytes, double memMaxBytes) {
        MetricIngestDto dto = new MetricIngestDto();
        dto.setTs(System.currentTimeMillis());
        MetricIngestDto.MetricData data = new MetricIngestDto.MetricData();
        data.setCpuUsage(cpuUsage);
        data.setMemoryUsed(memUsedBytes);
        data.setMemoryMax(memMaxBytes);
        dto.setData(data);
        return dto;
    }

    private ServerMetric metric(double cpuPercent, double memUsedMb, double memMaxMb) {
        return ServerMetric.builder()
                .server(server)
                .cpuUsage(cpuPercent)
                .memoryUsedMb(memUsedMb)
                .memoryMaxMb(memMaxMb)
                .capturedAt(LocalDateTime.now())
                .build();
    }
}
