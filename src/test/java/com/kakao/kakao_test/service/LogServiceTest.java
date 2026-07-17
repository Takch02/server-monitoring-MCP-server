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
    void 시간창내_로그없음_빈결과반환() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(Collections.emptyList());

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getErrorCount()).isZero();
        assertThat(result.getSummary()).contains("24시간");
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void 시간창_24h_파라미터가_현재시각_기준으로_전달됨() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(Collections.emptyList());

        logService.analyzeErrorLogs("test-server");

        // since 파라미터가 현재 시각 기준 24h 이내인지 확인
        then(serverLogRepository).should().findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(
                eq(server),
                argThat(since -> since.isAfter(LocalDateTime.now().minusHours(25))
                              && since.isBefore(LocalDateTime.now().minusHours(23)))
        );
    }

    // ===== analyzeErrorLogs — 에러 필터링 =====

    @Test
    void INFO_WARN로그만있으면_에러없음() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(new ArrayList<>(List.of(
                        log("INFO", "정상 처리 완료"),
                        log("WARN", "경고 메시지")
                )));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getErrorCount()).isZero();
        assertThat(result.getSummary()).contains("에러가 없습니다");
    }

    @Test
    void ERROR로그_감지됨() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(List.of(
                        log("ERROR", "NullPointerException at UserService.java:42")
                ));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getErrorCount()).isPositive();
        assertThat(result.getRecentErrors()).anyMatch(e -> e.contains("NullPointerException"));
    }

    @Test
    void Exception힌트있는INFO로그_에러로포함됨() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(List.of(
                        log("INFO", "Caused by: java.lang.NullPointerException")
                ));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getErrorCount()).isPositive();
    }

    @Test
    void 중복로그_반복횟수요약표시() {
        String sameMsg = "Connection refused to DB";
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(new ArrayList<>(List.of(
                        log("ERROR", sameMsg),
                        log("ERROR", sameMsg),
                        log("ERROR", sameMsg)
                )));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getRecentErrors()).anyMatch(e -> e.contains("반복"));
    }

    @Test
    void 메시지500자초과시_생략표시() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(List.of(
                        log("ERROR", "E".repeat(600))
                ));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.getRecentErrors()).anyMatch(e -> e.contains("생략됨"));
    }

    // ===== analyzeErrorLogs — 상한(truncated) =====

    @Test
    void 로그_100건이하면_truncated_false() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(errorLogs(50));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void 로그_101건이면_truncated_true_이고_100건만반환() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(errorLogs(101));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        assertThat(result.isTruncated()).isTrue();
        // 중복 제거 후 실제 에러 건수가 100건 이하인지 검증
        assertThat(result.getErrorCount()).isLessThanOrEqualTo(100);
    }

    @Test
    void truncated시_summary에_상한도달_문구포함() {
        given(serverLogRepository.findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(eq(server), any()))
                .willReturn(errorLogs(101));

        ErrorLogAnalysisDto result = logService.analyzeErrorLogs("test-server");

        // toString()에 상한 도달 경고가 포함되는지 확인
        assertThat(result.toString()).contains("100건 상한 도달");
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

    private ServerLog log(String level, String message) {
        return ServerLog.builder()
                .server(server)
                .eventId(UUID.randomUUID().toString())
                .level(level)
                .message(message)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    /** 고유 메시지를 가진 ERROR 로그 n개 생성 */
    private List<ServerLog> errorLogs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> log("ERROR", "Error message #" + i))
                .collect(java.util.stream.Collectors.toList());
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
