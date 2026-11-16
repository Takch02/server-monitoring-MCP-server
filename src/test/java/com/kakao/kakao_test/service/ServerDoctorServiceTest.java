package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.DiagnosticProperties;
import com.kakao.kakao_test.domain.ServerHealthEvent;
import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import com.kakao.kakao_test.repository.ServerHealthEventRepository;
import com.kakao.kakao_test.repository.ServerMetricRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class ServerDoctorServiceTest {
    @Mock LogService logs;
    @Mock TargetServerRepository servers;
    @Mock ServerMetricRepository metrics;
    @Mock ServerHealthEventRepository healthEvents;
    @Mock DiscordNotificationService notifications;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneId.systemDefault());
    private final TargetServer server = TargetServer.builder().serverName("test").build();
    private ServerDoctorService service;

    @BeforeEach
    void setUp() {
        DiagnosticProperties properties = new DiagnosticProperties();
        MetricService metricService = new MetricService(servers, metrics, notifications, clock, properties);
        HealthService healthService = new HealthService(servers, healthEvents, clock, properties);
        service = new ServerDoctorService(logs, metricService, healthService);
        given(servers.getByServerName("test")).willReturn(server);
    }

    @Test
    void 오류로그가_없어도_데이터가없으면_정상을_단정하지않는다() {
        logs(0);
        assertThat(service.diagnoseForMcp("test", 24))
                .contains("Health: UNKNOWN", "자원 상태: UNKNOWN", "로그 부재만으로")
                .doesNotContain("Healthy", "자원 상태: NORMAL");
    }

    @Test
    void Health가_DOWN이면_정상자원과_오류로그부재가_이를_가리지않는다() {
        logs(0);
        health("DOWN", 503, 5);
        normalMetrics();
        assertThat(service.diagnoseForMcp("test", 24))
                .contains("Health: DOWN", "자원 상태: NORMAL").doesNotContain("Healthy");
    }

    @Test
    void 오래된_Health는_UNKNOWN으로_표시한다() {
        logs(0);
        health("UP", 200, 61);
        normalMetrics();
        assertThat(service.diagnoseForMcp("test", 24))
                .contains("Health: UNKNOWN", "자원 상태: NORMAL").doesNotContain("Healthy");
    }

    @Test
    void Health가_UP여도_지표누락을_정상으로_대체하지않는다() {
        logs(0);
        health("UP", 200, 5);
        assertThat(service.diagnoseForMcp("test", 24))
                .contains("Health: UP", "자원 상태: UNKNOWN").doesNotContain("Healthy");
    }

    @Test
    void Health가_UP여도_애플리케이션_오류는_함께_전달한다() {
        logs(1);
        health("UP", 200, 5);
        normalMetrics();
        assertThat(service.diagnoseForMcp("test", 24))
                .contains("Health: UP", "에러 수: 1건", "NullPointerException").doesNotContain("Healthy");
    }

    private void logs(int count) {
        given(logs.analyzeErrorLogs("test", 24)).willReturn(new ErrorLogAnalysisDto("test",
                count == 0 ? List.of() : List.of("NullPointerException"), count, "", 24, false, Map.of(), Map.of()));
    }

    private void health(String status, int http, long age) {
        given(healthEvents.findTop1ByServerNameOrderByTsDesc("test")).willReturn(Optional.of(
                ServerHealthEvent.builder().serverName("test").status(status).httpStatus(http)
                        .ts(clock.millis() - age * 1000).latencyMs(3).build()));
    }

    private void normalMetrics() {
        given(metrics.findByServerAndCapturedAtBetweenOrderByCapturedAtDesc(eq(server), any(), any()))
                .willReturn(List.of(ServerMetric.builder().server(server).cpuUsage(10.0)
                        .memoryUsedMb(100.0).memoryMaxMb(1000.0)
                        .capturedAt(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())).build()));
    }
}
