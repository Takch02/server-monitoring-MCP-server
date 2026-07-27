package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import com.kakao.kakao_test.dto.IngestResultDto;
import com.kakao.kakao_test.dto.LogEventDto;
import com.kakao.kakao_test.exception.UnauthorizedException;
import com.kakao.kakao_test.repository.ServerLogRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class LogServiceTest {

    @Mock TargetServerRepository targetServerRepository;
    @Mock ServerLogRepository serverLogRepository;
    @Mock DiscordNotificationService discordNotificationService;
    @Mock JdbcTemplate jdbcTemplate;

    @InjectMocks LogService logService;

    private TargetServer server;

    @BeforeEach
    void setUp() {
        server = TargetServer.builder()
                .serverName("test-server")
                .mcpToken("valid-token")
                .build();
        given(targetServerRepository.getByServerName("test-server")).willReturn(server);
    }

    // ===== analyzeErrorLogs — 시간 창 =====

    @Test
    void 시간창내_에러없음_빈결과반환() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(Collections.emptyList());

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getErrorCount()).isZero();
        assertThat(result.getSummary()).contains("24시간");
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void 기본값_24h_since_파라미터_전달됨() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(Collections.emptyList());

        logService.analyzeErrorLogs("test-server");

        then(serverLogRepository).should().findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(
                eq(server),
                eq("ERROR"),
                argThat(since -> since.isAfter(LocalDateTime.now().minusHours(25))
                              && since.isBefore(LocalDateTime.now().minusHours(23)))
        );
    }

    @Test
    void 커스텀_168h_since_파라미터_전달됨() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(Collections.emptyList());

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server", 168);

        assertThat(result.getWindowHours()).isEqualTo(168);
        assertThat(result.getSummary()).contains("168시간");
        then(serverLogRepository).should().findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(
                eq(server),
                eq("ERROR"),
                argThat(since -> since.isAfter(LocalDateTime.now().minusHours(169))
                              && since.isBefore(LocalDateTime.now().minusHours(167)))
        );
    }

    @Test
    void windowHours가_결과에_포함됨() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(errorLog("some error"))));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server", 48);

        assertThat(result.getWindowHours()).isEqualTo(48);
        assertThat(result.toString()).contains("48h");
    }

    // ===== analyzeErrorLogs — 에러 필터링 =====

    @Test
    void ERROR로그_감지됨() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(errorLog("NullPointerException at UserService.java:42"))));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getErrorCount()).isPositive();
        assertThat(result.getRecentErrors()).anyMatch(e -> e.contains("NullPointerException"));
    }

    @Test
    void 중복로그_반복횟수요약표시() {
        String sameMsg = "Connection refused to DB";
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(
                        errorLog(sameMsg),
                        errorLog(sameMsg),
                        errorLog(sameMsg)
                )));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getRecentErrors()).anyMatch(e -> e.contains("반복"));
    }

    @Test
    void 중복로그가_축약되어도_errorCount는_실제_로그건수를_반환() {
        String sameMsg = "Connection refused to DB";
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(
                        errorLog(sameMsg),
                        errorLog(sameMsg),
                        errorLog(sameMsg)
                )));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getErrorCount()).isEqualTo(3);
    }

    @Test
    void 메시지500자초과시_생략표시() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(errorLog("E".repeat(600)))));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getRecentErrors()).anyMatch(e -> e.contains("생략됨"));
    }

    // ===== analyzeErrorLogs — 상한(truncated) =====

    @Test
    void 로그_100건이하면_truncated_false() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(errorLogs(50));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void 로그_101건이면_truncated_true_이고_100건만반환() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(errorLogs(101));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getErrorCount()).isLessThanOrEqualTo(100);
    }

    @Test
    void truncated시_summary에_상한도달_문구포함() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(errorLogs(101));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.toString()).contains("상한 도달");
    }

    // ===== analyzeErrorLogs — 예외 타입 / HTTP 상태 코드 집계 =====

    @Test
    void 예외타입별_건수가_집계됨() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(
                        errorLog("java.lang.NullPointerException: null"),
                        errorLog("java.lang.NullPointerException: null (route=200)"),
                        errorLog("java.net.SocketTimeoutException: 버스 API 연결 시간 초과")
                )));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getExceptionTypeCounts())
                .containsEntry("NullPointerException", 2L)
                .containsEntry("SocketTimeoutException", 1L);
    }

    @Test
    void 문맥없는_숫자는_상태코드로_오탐되지않음() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(
                        errorLog("버스 API ConnectionTimeout — route=200"),
                        errorLog("캐시 미스로 인한 NPE — route=100 seq=1")
                )));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getHttpStatusCounts()).isEmpty();
    }

    @Test
    void status코드_문맥이있으면_HTTP상태코드로_집계됨() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(new ArrayList<>(List.of(
                        errorLog("외부 API 호출 실패: statusCode=500"),
                        errorLog("요청 거부: status=404"),
                        errorLog("요청 거부: status=404")
                )));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getHttpStatusCounts())
                .containsEntry("500", 1L)
                .containsEntry("404", 2L);
    }

    @Test
    void 에러없으면_집계도_빈맵() {
        given(serverLogRepository.findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), eq("ERROR"), any()))
                .willReturn(Collections.emptyList());

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getExceptionTypeCounts()).isEmpty();
        assertThat(result.getHttpStatusCounts()).isEmpty();
    }

    // ===== ingestLogs =====

    @Test
    void ingest_빈이벤트_조기반환() {
        IngestResultDto result = logService.ingestLogs("test-server", "valid-token", null, List.of());

        assertThat(result.getAcceptedCount()).isZero();
        assertThat(result.getMessage()).contains("수신할 로그가 없습니다");
        then(jdbcTemplate).shouldHaveNoInteractions();
    }

    @Test
    void ingest_잘못된토큰_예외발생() {
        LogEventDto event = mockEvent("evt1", "ERROR", "error msg");

        assertThatThrownBy(() ->
                logService.ingestLogs("test-server", "wrong-token", null, List.of(event))
        ).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void ingest_정상저장_batchUpdate호출() {
        given(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .willReturn(new int[]{1});

        IngestResultDto result = logService.ingestLogs(
                "test-server", "valid-token", null,
                List.of(mockEvent("evt1", "ERROR", "error message"))
        );

        assertThat(result.getAcceptedCount()).isEqualTo(1);
        then(jdbcTemplate).should().batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    // ===== helpers =====

    private ServerLog errorLog(String message) {
        return ServerLog.builder()
                .server(server)
                .eventId(UUID.randomUUID().toString())
                .level("ERROR")
                .message(message)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    private List<ServerLog> errorLogs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> errorLog("Error message #" + i))
                .collect(Collectors.toList());
    }

    private LogEventDto mockEvent(String eventId, String level, String message) {
        LogEventDto e = new LogEventDto();
        ReflectionTestUtils.setField(e, "eventId", eventId);
        ReflectionTestUtils.setField(e, "level", level);
        ReflectionTestUtils.setField(e, "message", message);
        ReflectionTestUtils.setField(e, "ts", System.currentTimeMillis());
        return e;
    }
}
