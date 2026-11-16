package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.DiagnosticProperties;
import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.repository.ServerMetricRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class MetricServiceFreshnessTest {
    @Mock TargetServerRepository servers;
    @Mock ServerMetricRepository metrics;
    @Mock DiscordNotificationService notifications;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneId.systemDefault());
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    private final DiagnosticProperties properties = new DiagnosticProperties();
    private final TargetServer server = TargetServer.builder().serverName("test").build();
    private MetricService service;

    @BeforeEach
    void setUp() {
        service = new MetricService(servers, metrics, notifications, clock, properties);
        given(servers.getByServerName("test")).willReturn(server);
    }

    @Test
    void 조회기간_밖의_과거지표는_현재상태에_사용하지않는다() {
        given(metrics.findTopByServerOrderByCapturedAtDesc(server))
                .willReturn(Optional.of(metric(now.minusDays(1), 10.0, 1000.0)));
        String result = service.getMetricTrend("test");
        assertThat(result).contains("UNKNOWN", "마지막 관측 시각").doesNotContain("NORMAL", "평균 CPU");
        then(metrics).should().findByServerAndCapturedAtBetweenOrderByCapturedAtDesc(server, now.minusMinutes(10), now);
    }

    @Test
    void 조회기간_내에_있어도_61초_지난_지표는_현재판정불가() {
        history(metric(now.minusSeconds(61), 10.0, 1000.0));
        assertThat(service.getMetricTrend("test")).contains("UNKNOWN", "오래되어").doesNotContain("NORMAL");
    }

    @Test
    void 정확히_60초된_지표는_관측범위내_정상() {
        history(metric(now.minusSeconds(60), 10.0, 1000.0));
        assertThat(service.getMetricTrend("test")).contains("NORMAL", "JVM 메모리", "관측 1건");
    }

    @Test
    void 최신성_기준과_관측기간을_설정할수있다() {
        properties.setStaleSeconds(120);
        properties.setMetricWindowMinutes(5);
        history(metric(now.minusSeconds(90), 10.0, 1000.0));
        assertThat(service.getMetricTrend("test")).contains("NORMAL");
        then(metrics).should().findByServerAndCapturedAtBetweenOrderByCapturedAtDesc(server, now.minusMinutes(5), now);
    }

    @Test
    void 미래_시각의_지표로_정상판정하지않는다() {
        history(metric(now.plusSeconds(1), 10.0, 1000.0));
        assertThat(service.getMetricTrend("test")).contains("UNKNOWN").doesNotContain("NORMAL");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void 최대메모리값이_유효하지않으면_정상판정하지않는다(double max) {
        history(metric(now, 10.0, max));
        assertThat(service.getMetricTrend("test")).contains("UNKNOWN").doesNotContain("NORMAL", "NaN", "Infinity");
    }

    @Test
    void 누락된_CPU값은_정상판정하지않는다() {
        history(metric(now, null, 1000.0));
        assertThat(service.getMetricTrend("test")).contains("UNKNOWN");
    }

    @Test
    void 누락된_최대메모리값은_정상판정하지않는다() {
        history(metric(now, 10.0, null));
        assertThat(service.getMetricTrend("test")).contains("UNKNOWN");
    }

    @Test
    void 일부지표가_잘못되었으면_전체구간을_정상으로_단정하지않는다() {
        history(metric(now, 10.0, 1000.0), metric(now.minusSeconds(10), Double.NaN, 1000.0));
        assertThat(service.getMetricTrend("test")).contains("UNKNOWN").doesNotContain("NORMAL");
    }

    private void history(ServerMetric... samples) {
        given(metrics.findByServerAndCapturedAtBetweenOrderByCapturedAtDesc(eq(server), any(), any()))
                .willReturn(List.of(samples));
    }

    private ServerMetric metric(LocalDateTime at, Double cpu, Double max) {
        return ServerMetric.builder().server(server).cpuUsage(cpu).memoryUsedMb(100.0)
                .memoryMaxMb(max).capturedAt(at).build();
    }
}
