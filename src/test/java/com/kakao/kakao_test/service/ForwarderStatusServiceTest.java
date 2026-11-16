package com.kakao.kakao_test.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kakao.kakao_test.config.DiagnosticProperties;
import com.kakao.kakao_test.domain.ForwarderStatus;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.ForwarderStatusIngestDto;
import com.kakao.kakao_test.exception.UnauthorizedException;
import com.kakao.kakao_test.repository.ForwarderStatusRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ForwarderStatusServiceTest {
    @Mock TargetServerRepository servers;
    @Mock ForwarderStatusRepository statuses;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
    private final TargetServer server = TargetServer.builder().serverName("test").mcpToken("token").build();
    private ForwarderStatusService service;
    private ObjectNode report;

    @BeforeEach
    void setUp() throws Exception {
        server.setId(1L);
        service = new ForwarderStatusService(servers, statuses, mapper, clock, new DiagnosticProperties());
        try (var source = getClass().getResourceAsStream("/fixtures/forwarder-status-v1.json")) {
            report = (ObjectNode) mapper.readTree(source);
        }
    }

    @Test
    void 잘못된토큰은_상태를_조회하거나_갱신하지않는다() {
        given(servers.findForStatusUpdate("test")).willReturn(Optional.of(server));
        assertThatThrownBy(() -> service.ingest("test", "wrong", dto())).isInstanceOf(UnauthorizedException.class);
        then(statuses).shouldHaveNoInteractions();
    }

    @Test
    void 최초보고를_서버수신시각으로_저장한다() throws Exception {
        given(servers.findForStatusUpdate("test")).willReturn(Optional.of(server));
        given(statuses.findById(1L)).willReturn(Optional.empty());
        assertThat(service.ingest("test", "token", dto())).isTrue();
        var saved = org.mockito.ArgumentCaptor.forClass(ForwarderStatus.class);
        then(statuses).should().save(saved.capture());
        assertThat(saved.getValue().getReceivedAt()).isEqualTo(clock.instant());
        assertThat(saved.getValue().getServerId()).isEqualTo(1L);
        assertThat(saved.getValue().getSnapshotJson()).doesNotContain("token");
    }

    @Test
    void 같은실행의_중복이나_역순보고는_최신성과_수신시각을_갱신하지않는다() throws Exception {
        ForwarderStatus latest = row(clock.instant().minusSeconds(30));
        given(servers.findForStatusUpdate("test")).willReturn(Optional.of(server));
        given(statuses.findById(1L)).willReturn(Optional.of(latest));
        assertThat(service.ingest("test", "token", dto())).isFalse();
        report.put("sequence", 0);
        assertThat(service.ingest("test", "token", dto())).isFalse();
        assertThat(latest.getReceivedAt()).isEqualTo(clock.instant().minusSeconds(30));
        then(statuses).should(never()).save(any());
    }

    @Test
    void 새순번의_복구보고가_기존_오류를_교체한다() throws Exception {
        stream("metrics").put("last_send_error", "timeout");
        ForwarderStatus latest = row(clock.instant().minusSeconds(5));
        given(servers.findForStatusUpdate("test")).willReturn(Optional.of(server));
        given(servers.getByServerName("test")).willReturn(server);
        given(statuses.findById(1L)).willReturn(Optional.of(latest));
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: DEGRADED", "timeout");
        report.put("sequence", 2);
        stream("metrics").putNull("last_send_error");
        assertThat(service.ingest("test", "token", dto())).isTrue();
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: OK").doesNotContain("timeout");
    }

    @Test
    void 재시작하면_새실행의_초기화된카운터로_교체하고_이전실행의_지연보고는_무시한다() throws Exception {
        ForwarderStatus latest = row(clock.instant());
        given(servers.findForStatusUpdate("test")).willReturn(Optional.of(server));
        given(statuses.findById(1L)).willReturn(Optional.of(latest));
        ObjectNode oldReport = report.deepCopy();
        report.put("run_id", "22222222-2222-4222-8222-222222222222");
        report.put("started_at", report.get("started_at").asDouble() + 50);
        stream("logs").put("sent_items", 0);
        assertThat(service.ingest("test", "token", dto())).isTrue();
        assertThat(mapper.readTree(latest.getSnapshotJson()).path("streams").path("logs").path("sent_items").asLong()).isZero();
        report = oldReport;
        report.put("sequence", 999);
        assertThat(service.ingest("test", "token", dto())).isFalse();
        assertThat(latest.getRunId()).startsWith("22222222");
    }

    @Test
    void 상태보고가없으면_UNKNOWN이다() {
        given(servers.getByServerName("test")).willReturn(server);
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: UNKNOWN", "보고가 없습니다");
    }

    @Test
    void 서버수신후_75초경계는_허용하고_초과와_미래수신은_UNKNOWN이다() throws Exception {
        given(servers.getByServerName("test")).willReturn(server);
        for (long age : new long[]{75, 76, -1}) {
            given(statuses.findById(1L)).willReturn(Optional.of(row(clock.instant().minusSeconds(age))));
            assertThat(service.getStatusForMcp("test")).contains(age == 75 ? "Forwarder: OK" : "Forwarder: UNKNOWN");
        }
    }

    @Test
    void 로컬시계차이가있어도_중앙최신성은_서버수신시각을_사용한다() throws Exception {
        report.put("updated_at", 1000.0);
        report.put("started_at", 900.0);
        for (var name : new String[]{"logs", "log_sender", "metrics", "health"}) {
            stream(name).put("last_poll_at", 1000.0);
        }
        given(servers.getByServerName("test")).willReturn(server);
        given(statuses.findById(1L)).willReturn(Optional.of(row(clock.instant())));
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: OK");
    }

    @Test
    void 수집실패와_루프중단과_최근폐기는_보고가도착해도_DEGRADED이다() throws Exception {
        given(servers.getByServerName("test")).willReturn(server);
        stream("metrics").put("last_collection_error", "missing_or_invalid_metric");
        stream("log_sender").put("last_poll_at", report.get("updated_at").asDouble() - 76);
        stream("logs").put("dropped_items", 10);
        stream("logs").put("last_drop_at", report.get("updated_at").asDouble());
        stream("logs").put("last_drop_reason", "queue_full");
        given(statuses.findById(1L)).willReturn(Optional.of(row(clock.instant())));
        assertThat(service.getStatusForMcp("test"))
                .contains("Forwarder: DEGRADED", "missing_or_invalid_metric", "루프 활동 지연", "queue_full", "누락");
    }

    @Test
    void 로그가없어도_읽기루프정상과_비활성수집기를_장애로_오판하지않는다() throws Exception {
        stream("logs").put("collected_items", 0);
        stream("metrics").put("enabled", false);
        stream("metrics").put("state", "disabled");
        given(servers.getByServerName("test")).willReturn(server);
        given(statuses.findById(1L)).willReturn(Optional.of(row(clock.instant())));
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: OK", "metrics: 비활성", "과거 전체 수집을 보장하지");
    }

    @Test
    void 과거폐기는_현재복구후에도_누락가능성을_알린다() throws Exception {
        stream("logs").put("dropped_items", 10);
        stream("logs").put("last_drop_at", report.get("updated_at").asDouble() - 76);
        given(servers.getByServerName("test")).willReturn(server);
        given(statuses.findById(1L)).willReturn(Optional.of(row(clock.instant())));
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: OK", "누적 폐기=10", "누락되었을 수");
    }

    @Test
    void 긴수집주기는_보고된_루프한계까지_정상대기를_허용한다() throws Exception {
        given(servers.getByServerName("test")).willReturn(server);
        stream("health").put("poll_stale_seconds", 212);
        stream("health").put("last_poll_at", report.get("updated_at").asDouble() - 180);
        given(statuses.findById(1L)).willReturn(Optional.of(row(clock.instant())));
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: OK");
        stream("health").put("last_poll_at", report.get("updated_at").asDouble() - 213);
        given(statuses.findById(1L)).willReturn(Optional.of(row(clock.instant())));
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: DEGRADED", "루프 활동 지연");
    }

    private ObjectNode stream(String name) {
        return (ObjectNode) report.path("streams").path(name);
    }

    private ForwarderStatusIngestDto dto() throws Exception {
        return mapper.treeToValue(report, ForwarderStatusIngestDto.class);
    }

    private ForwarderStatus row(Instant receivedAt) throws Exception {
        var dto = dto();
        ForwarderStatus row = new ForwarderStatus(1L);
        row.replace(dto.runId(), dto.startedAt(), dto.sequence(), receivedAt, mapper.writeValueAsString(dto));
        return row;
    }
}
