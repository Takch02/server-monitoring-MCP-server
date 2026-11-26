package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.*;
import com.kakao.kakao_test.exception.InvalidRequestException;
import com.kakao.kakao_test.exception.UnauthorizedException;
import com.kakao.kakao_test.repository.ServerLogRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본적으로 읽기 전용 (성능 최적화)
public class LogService {

    private final TargetServerRepository targetServerRepository;
    private final ServerLogRepository serverLogRepository;
    private final LogAlertDestination alertDestination;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 서버 이름 가져오기 (없으면 에러)
     */
    public TargetServer getServerOrThrow(String name) {
        return targetServerRepository.getByServerName(name);
    }


    /**
     * Forwarder의 로그를 일괄 저장한다. 워커는 커밋된 신규 ERROR만 읽어 서버별 알림을 보낸다.
     */
    @Retryable(
            value = {CannotAcquireLockException.class, DeadlockLoserDataAccessException.class}, // 데드락 에러 발생 시에만
            maxAttempts = 3,                                  // 최대 3번 재시도
            backoff = @Backoff(delay = 200)                   // 0.2초 대기 후 재시도
    )
    @Transactional
    public IngestResultDto ingestLogs(String serverName, String mcpToken, String discordWebhookUrl, List<LogEventDto> events) {
        TargetServer server = getServerOrThrow(serverName);
        verifyToken(server, mcpToken);

        if (events == null || events.isEmpty()) {
            return new IngestResultDto(serverName, 0, "수신할 로그가 없습니다.");
        }

        if (events.stream().anyMatch(event -> event == null || event.getTs() == null)) {
            throw new InvalidRequestException("모든 로그에 발생 시각(ts)이 필요합니다.");
        }

        List<LogEventDto> validEvents = events.stream()
                .filter(e -> e.getEventId() != null && !e.getEventId().isBlank())
                .toList();

        // 1) 알림을 보낼 오류가 있을 때만 서버의 웹훅 설정을 확인한다.
        boolean queueErrors = discordWebhookUrl != null && !discordWebhookUrl.isBlank()
                && validEvents.stream().anyMatch(event -> "ERROR".equalsIgnoreCase(event.getLevel()));
        if (queueErrors) alertDestination.remember(server.getId(), discordWebhookUrl);

        // 2) INFO/WARN도 함께 저장한다. 재전송 행은 기존 발송 상태를 그대로 유지한다.
        insertBatch(server, validEvents, queueErrors);

        log.info("{} 서버로부터 로그 {}개 수신됨", serverName, validEvents.size());
        return new IngestResultDto(serverName, events.size(), "로그 수신 완료");
    }

    private static final String INSERT_LOG = """
            INSERT INTO server_log (event_id, server_id, level, message, created_at, occurred_at, error_group_key, alert_delivery)
            VALUES (?, ?, ?, ?, NOW(), ?, ?, ?)
            """;

    private void insertBatch(TargetServer server, List<LogEventDto> events, boolean queueErrors) {
        if (events.isEmpty()) return;
        jdbcTemplate.batchUpdate(INSERT_LOG + " ON DUPLICATE KEY UPDATE event_id = server_log.event_id",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        bindLog(ps, server, events.get(i), queueErrors);
                    }

                    @Override
                    public int getBatchSize() {
                        return events.size();
                    }
                });
    }

    private void bindLog(PreparedStatement ps, TargetServer server, LogEventDto event, boolean queueErrors) throws SQLException {
        ps.setString(1, event.getEventId());
        ps.setLong(2, server.getId());
        ps.setString(3, event.getLevel());
        ps.setString(4, event.getMessage());
        ps.setObject(5, convertTimestamp(event.getTs()));
        boolean error = "ERROR".equalsIgnoreCase(event.getLevel());
        ps.setString(6, error ? ErrorFingerprint.of(event.getMessage()) : null);
        ps.setString(7, error && queueErrors ? "PENDING" : null);
    }

    /**
     * 로그 분석 (LLM 도구용)
     * DB에서 최근 로그를 조회하여 요약
     */
    static final int LOG_LIMIT = 100;
    static final int DEFAULT_WINDOW_HOURS = 24;

    // 예외 타입 추출: "java.lang.NullPointerException" 같은 FQCN에서 단순 클래스명만 캡처
    private static final Pattern EXCEPTION_TYPE_PATTERN =
            Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_]*\\.)*([A-Za-z_][A-Za-z0-9_]*(?:Exception|Error))\\b");

    // HTTP 상태 코드 추출: "route=200"처럼 문맥 없는 숫자를 오탐하지 않도록 status/HTTP 문맥이 붙은 경우만 매칭
    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile(
            "(?i)status(?:\\s*code)?\\s*[:=]\\s*([1-5]\\d{2})\\b" +
            "|\\bHTTP\\s+([1-5]\\d{2})\\b" +
            "|HTTP/\\d\\.\\d\"\\s+([1-5]\\d{2})\\b" +
            "|\\b([1-5]\\d{2})\\s+(?:OK|Created|Accepted|No Content|Bad Request|Unauthorized|Forbidden|Not Found|" +
            "Method Not Allowed|Conflict|Too Many Requests|Internal Server Error|Bad Gateway|Service Unavailable|Gateway Timeout)\\b"
    );

    public ErrorLogAnalysisDto analyzeErrorLogs(String name) {
        return analyzeErrorLogs(name, DEFAULT_WINDOW_HOURS);
    }

    public String fetchRecentLogs(String name, int windowHours) {
        TargetServer server = getServerOrThrow(name);

        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        List<ServerLog> fetched = serverLogRepository
                .findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(server, since);

        boolean truncated = fetched.size() > LOG_LIMIT;
        List<ServerLog> logs = truncated ? fetched.subList(0, LOG_LIMIT) : fetched;
        Collections.reverse(logs);

        if (logs.isEmpty()) {
            return "✅ 최근 " + windowHours + "시간 내 수집된 로그가 없습니다.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### 📋 최근 로그 (Server: %s, 최근 %dh, %d건%s)\n\n",
                name, windowHours, logs.size(),
                truncated ? " — ⚠️ 상한 도달, 실제 로그는 더 많음" : ""));
        sb.append("```text\n");
        for (ServerLog log : logs) {
            sb.append(log.getOccurredAt())
              .append(" [").append(safe(log.getLevel())).append("] ")
              .append(safe(log.getMessage()).length() > 300
                      ? safe(log.getMessage()).substring(0, 300) + " ..." : safe(log.getMessage()))
              .append("\n");
        }
        sb.append("```");
        return sb.toString();
    }

    public ErrorLogAnalysisDto analyzeErrorLogs(String name, int windowHours) {
        TargetServer server = getServerOrThrow(name);

        // DB에서 ERROR 레벨만 101건 조회 (idx_log_level 인덱스 사용)
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        List<ServerLog> fetched = serverLogRepository
                .findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(server, "ERROR", since);

        boolean truncated = fetched.size() > LOG_LIMIT;
        List<ServerLog> errorLogs = truncated ? fetched.subList(0, LOG_LIMIT) : fetched;

        // 시간순으로 뒤집어 사건의 순서대로 보여줌
        Collections.reverse(errorLogs);

        if (errorLogs.isEmpty()) {
            return new ErrorLogAnalysisDto(name, List.of(), 0,
                    "✅ 최근 " + windowHours + "시간 내 수집된 에러 로그가 없습니다.", windowHours, truncated,
                    Map.of(), Map.of());
        }

        List<String> errors = new ArrayList<>();
        String lastMsg = null;
        int duplicateCount = 0;

        for (ServerLog log : errorLogs) {
            String msg = safe(log.getMessage());

            // (A) 중복 제거 로직
            if (msg.equals(lastMsg)) {
                duplicateCount++;
                continue;
            }

            if (duplicateCount > 0) {
                errors.add("   ㄴ (위와 동일한 에러가 " + duplicateCount + "번 더 반복되었습니다.)");
                duplicateCount = 0;
            }

            // (B) 길이 제한
            String displayMsg = msg.length() > 500 ? msg.substring(0, 500) + "\n   ... (생략됨) ..." : msg;

            errors.add(log.getOccurredAt() + " [ERROR] " + displayMsg);
            lastMsg = msg;
        }

        if (duplicateCount > 0) {
            errors.add("   ㄴ (위와 동일한 에러가 " + duplicateCount + "번 더 반복되었습니다.)");
        }

        if (errors.isEmpty()) {
            String summary = truncated
                    ? "✅ 분석 범위(최근 " + windowHours + "h, 100건 샘플)에서는 에러 없음 — 범위 밖 로그는 확인되지 않았습니다."
                    : "✅ 최근 " + windowHours + "시간 내 에러가 없습니다.";
            return new ErrorLogAnalysisDto(name, List.of(), 0, summary, windowHours, truncated,
                    Map.of(), Map.of());
        }

        // (C) 예외 타입 / HTTP 상태 코드 집계 (표시 중인 100건 샘플 기준)
        Map<String, Long> exceptionTypeCounts = new LinkedHashMap<>();
        Map<String, Long> httpStatusCounts = new LinkedHashMap<>();
        for (ServerLog log : errorLogs) {
            String msg = safe(log.getMessage());

            String exceptionType = extractExceptionType(msg);
            if (exceptionType != null) {
                exceptionTypeCounts.merge(exceptionType, 1L, Long::sum);
            }

            String httpStatus = extractHttpStatus(msg);
            if (httpStatus != null) {
                httpStatusCounts.merge(httpStatus, 1L, Long::sum);
            }
        }

        return new ErrorLogAnalysisDto(name, errors, errorLogs.size(),
                "⚠️ 최근 " + windowHours + "시간 내 에러 로그가 발견되었습니다.", windowHours, truncated,
                exceptionTypeCounts, httpStatusCounts);
    }

    private String extractExceptionType(String message) {
        Matcher m = EXCEPTION_TYPE_PATTERN.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private String extractHttpStatus(String message) {
        Matcher m = HTTP_STATUS_PATTERN.matcher(message);
        if (!m.find()) return null;
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) return m.group(i);
        }
        return null;
    }

    // --- 유틸리티 메서드 ---

    // epoch 밀리초를 로그 발생 시각으로 변환한다.
    private LocalDateTime convertTimestamp(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private void verifyToken(TargetServer server, String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("X-MCP-TOKEN 헤더가 필요합니다.");
        }
        // Entity 필드명(mcpToken)에 맞춤
        if (!server.getMcpToken().equals(token)) {
            throw new UnauthorizedException("토큰이 유효하지 않습니다.");
        }
    }

}