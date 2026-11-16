package com.kakao.kakao_test.mcp;

import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import com.kakao.kakao_test.service.HealthService;
import com.kakao.kakao_test.service.ForwarderStatusService;
import com.kakao.kakao_test.service.LogService;
import com.kakao.kakao_test.service.ServerDoctorService;
import com.kakao.kakao_test.service.ServerRegisterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ServerDoctorMcpToolsTest {

    private static final String INVALID_SINCE_HOURS_RESPONSE =
            "{\"error\":\"INVALID_ARGUMENT\",\"field\":\"sinceHours\",\"message\":\"sinceHours must be between 1 and 168 hours\",\"min\":1,\"max\":168}";

    @Mock ServerDoctorService serverDoctorService;
    @Mock LogService logService;
    @Mock ServerRegisterService serverRegisterService;
    @Mock HealthService healthService;
    @Mock ForwarderStatusService forwarderStatusService;

    private ServerDoctorMcpTools mcpTools;

    @BeforeEach
    void setUp() {
        mcpTools = new ServerDoctorMcpTools(
                serverDoctorService, logService, serverRegisterService, healthService, forwarderStatusService
        );
    }

    @Test
    void sinceHours_누락시_진단은24시간기본값을사용한다() {
        given(serverDoctorService.diagnoseForMcp("test-server", 24)).willReturn("진단 결과");

        String result = mcpTools.diagnoseServer("test-server", null);

        assertThat(result).isEqualTo("진단 결과");
        then(serverDoctorService).should().diagnoseForMcp("test-server", 24);
    }

    @Test
    void sinceHours_최소값1은_진단에서허용한다() {
        given(serverDoctorService.diagnoseForMcp("test-server", 1)).willReturn("진단 결과");

        String result = mcpTools.diagnoseServer("test-server", 1);

        assertThat(result).isEqualTo("진단 결과");
        then(serverDoctorService).should().diagnoseForMcp("test-server", 1);
    }

    @Test
    void sinceHours_최대값168은_에러로그조회에서허용한다() {
        given(logService.analyzeErrorLogs("test-server", 168)).willReturn(emptyAnalysis(168));

        String result = mcpTools.fetchErrorLogs("test-server", 168);

        assertThat(result).contains("최근 168h");
        then(logService).should().analyzeErrorLogs("test-server", 168);
    }

    @Test
    void sinceHours_0이면_세도구모두_INVALID_ARGUMENT을반환한다() {
        assertThat(mcpTools.diagnoseServer("test-server", 0)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);
        assertThat(mcpTools.fetchErrorLogs("test-server", 0)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);
        assertThat(mcpTools.fetchRecentLogs("test-server", 0)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);

        then(serverDoctorService).shouldHaveNoInteractions();
        then(logService).shouldHaveNoInteractions();
    }

    @Test
    void sinceHours_음수면_세도구모두_INVALID_ARGUMENT을반환한다() {
        assertThat(mcpTools.diagnoseServer("test-server", -1)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);
        assertThat(mcpTools.fetchErrorLogs("test-server", -1)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);
        assertThat(mcpTools.fetchRecentLogs("test-server", -1)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);

        then(serverDoctorService).shouldHaveNoInteractions();
        then(logService).shouldHaveNoInteractions();
    }

    @Test
    void sinceHours_최대초과면_세도구모두_INVALID_ARGUMENT을반환한다() {
        assertThat(mcpTools.diagnoseServer("test-server", 169)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);
        assertThat(mcpTools.fetchErrorLogs("test-server", 169)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);
        assertThat(mcpTools.fetchRecentLogs("test-server", 169)).isEqualTo(INVALID_SINCE_HOURS_RESPONSE);

        then(serverDoctorService).shouldHaveNoInteractions();
        then(logService).shouldHaveNoInteractions();
    }

    @Test
    void sinceHours_정상범위는_최근로그조회에전달한다() {
        given(logService.fetchRecentLogs("test-server", 48)).willReturn("최근 로그");

        String result = mcpTools.fetchRecentLogs("test-server", 48);

        assertThat(result).isEqualTo("최근 로그");
        then(logService).should().fetchRecentLogs("test-server", 48);
    }

    @Test
    void 수집상태조회는_service의_보고중단_결과를_그대로반환한다() {
        given(forwarderStatusService.getStatusForMcp("test-server")).willReturn("Forwarder: UNKNOWN — 보고 중단");
        assertThat(mcpTools.getForwarderStatus("test-server")).isEqualTo("Forwarder: UNKNOWN — 보고 중단");
        then(forwarderStatusService).should().getStatusForMcp("test-server");
    }

    private ErrorLogAnalysisDto emptyAnalysis(int windowHours) {
        return new ErrorLogAnalysisDto(
                "test-server", List.of(), 0, "에러 없음", windowHours, false, Map.of(), Map.of()
        );
    }
}
