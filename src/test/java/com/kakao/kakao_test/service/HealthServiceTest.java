package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.ServerHealthEvent;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.HealthIngestDto;
import com.kakao.kakao_test.repository.ServerHealthEventRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock TargetServerRepository targetServerRepository;
    @Mock ServerHealthEventRepository healthEventRepository;
    @Mock ServerHeartbeatService serverHeartbeatService;
    @InjectMocks HealthService healthService;

    // ===== saveHealth =====

    @Test
    void 알수없는서버_IllegalArgumentException() {
        given(targetServerRepository.findByServerName("unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                healthService.saveHealth("unknown", dto("UP", 200, 0, null), "any-token")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void 잘못된토큰_SecurityException() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "correct-token")));

        assertThatThrownBy(() ->
                healthService.saveHealth("my-server", dto("UP", 200, 0, null), "wrong-token")
        ).isInstanceOf(SecurityException.class);
    }

    @Test
    void 정상저장_healthEvent_save호출() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));

        healthService.saveHealth("my-server", dto("UP", 200, 0, null), "token");

        then(healthEventRepository).should().save(any(ServerHealthEvent.class));
    }

    @Test
    void ts0이면_현재시간사용() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));
        long before = System.currentTimeMillis();

        healthService.saveHealth("my-server", dto("UP", 200, 0, null), "token");

        ArgumentCaptor<ServerHealthEvent> captor = ArgumentCaptor.forClass(ServerHealthEvent.class);
        then(healthEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getTs()).isGreaterThanOrEqualTo(before);
    }

    @Test
    void ts양수이면_dto값사용() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));
        long fixedTs = 1_700_000_000_000L;

        healthService.saveHealth("my-server", new HealthIngestDto(fixedTs, "UP", 100, 200, null), "token");

        ArgumentCaptor<ServerHealthEvent> captor = ArgumentCaptor.forClass(ServerHealthEvent.class);
        then(healthEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getTs()).isEqualTo(fixedTs);
    }

    @Test
    void normalizeStatus_null상태_200_UP저장() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));

        healthService.saveHealth("my-server", dto(null, 200, 0, null), "token");

        ArgumentCaptor<ServerHealthEvent> captor = ArgumentCaptor.forClass(ServerHealthEvent.class);
        then(healthEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("UP");
    }

    @Test
    void normalizeStatus_알수없는상태_503_UNKNOWN저장() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));

        healthService.saveHealth("my-server", dto("DEGRADED", 503, 0, null), "token");

        ArgumentCaptor<ServerHealthEvent> captor = ArgumentCaptor.forClass(ServerHealthEvent.class);
        then(healthEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void normalizeStatus_유효한DOWN상태_그대로저장() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));

        healthService.saveHealth("my-server", dto("DOWN", 503, 0, null), "token");

        ArgumentCaptor<ServerHealthEvent> captor = ArgumentCaptor.forClass(ServerHealthEvent.class);
        then(healthEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DOWN");
    }

    @Test
    void safeMessage_500자초과_잘라서저장() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));

        healthService.saveHealth("my-server", dto("UP", 200, 0, "A".repeat(600)), "token");

        ArgumentCaptor<ServerHealthEvent> captor = ArgumentCaptor.forClass(ServerHealthEvent.class);
        then(healthEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getMessage()).hasSize(500);
    }

    @Test
    void safeMessage_null_null저장() {
        given(targetServerRepository.findByServerName("my-server"))
                .willReturn(Optional.of(server("my-server", "token")));

        healthService.saveHealth("my-server", dto("UP", 200, 0, null), "token");

        ArgumentCaptor<ServerHealthEvent> captor = ArgumentCaptor.forClass(ServerHealthEvent.class);
        then(healthEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getMessage()).isNull();
    }

    // ===== getHealthStatusForMcp =====

    @Test
    void 데이터없음_IllegalArgumentException() {
        given(healthEventRepository.findTop1ByServerNameOrderByTsDesc("my-server"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                healthService.getHealthStatusForMcp("my-server")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("my-server");
    }

    @Test
    void getHealthStatus_정상_status포함문자열반환() {
        given(healthEventRepository.findTop1ByServerNameOrderByTsDesc("my-server"))
                .willReturn(Optional.of(event("UP", 200, 100L, System.currentTimeMillis() - 5_000)));

        String result = healthService.getHealthStatusForMcp("my-server");

        assertThat(result).contains("UP");
        assertThat(result).contains("200");
        assertThat(result).contains("100");
    }

    @Test
    void getHealthStatus_60초초과_staleYES() {
        long staleTs = System.currentTimeMillis() - 70_000;
        given(healthEventRepository.findTop1ByServerNameOrderByTsDesc("my-server"))
                .willReturn(Optional.of(event("UP", 200, 50L, staleTs)));

        String result = healthService.getHealthStatusForMcp("my-server");

        assertThat(result).contains("Health: UNKNOWN");
        assertThat(result).contains("마지막 응답이");
    }

    @Test
    void getHealthStatus_최근데이터_staleNO() {
        long recentTs = System.currentTimeMillis() - 10_000;
        given(healthEventRepository.findTop1ByServerNameOrderByTsDesc("my-server"))
                .willReturn(Optional.of(event("UP", 200, 50L, recentTs)));

        String result = healthService.getHealthStatusForMcp("my-server");

        assertThat(result).contains("Health: UP");
        assertThat(result).contains("ago");
    }

    // ===== helpers =====

    private TargetServer server(String name, String token) {
        return TargetServer.builder().serverName(name).mcpToken(token).build();
    }

    private HealthIngestDto dto(String status, int httpStatus, long ts, String message) {
        return new HealthIngestDto(ts, status, 100, httpStatus, message);
    }

    private ServerHealthEvent event(String status, int httpStatus, long latencyMs, long ts) {
        return ServerHealthEvent.builder()
                .serverName("my-server")
                .status(status)
                .httpStatus(httpStatus)
                .latencyMs(latencyMs)
                .ts(ts)
                .build();
    }
}
