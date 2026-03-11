package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.*;
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

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본적으로 읽기 전용 (성능 최적화)
public class LogService {

    private final TargetServerRepository targetServerRepository;
    private final ServerLogRepository serverLogRepository;
    private final DiscordNotificationService discordNotificationService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 서버 이름 가져오기 (없으면 에러)
     */
    public TargetServer getServerOrThrow(String name) {
        return targetServerRepository.getByServerName(name);
    }


    /**
     * 로그 수신(PUSH) 및 저장
     * 1. 토큰 검증
     * 2. DB 저장
     * 3. 에러 감지 시 카카오 알림
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

        List<LogEventDto> validEvents = events.stream()
                .filter(e -> e.getEventId() != null && !e.getEventId().isBlank())
                .toList();

        String sql = """
            INSERT INTO server_log (event_id, server_id, level, message, created_at, occurred_at)
            VALUES (?, ?, ?, ?, NOW(), ?)
            ON DUPLICATE KEY UPDATE event_id = event_id
            """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LogEventDto e = validEvents.get(i);
                ps.setString(1, e.getEventId());
                ps.setLong(2, server.getId());
                ps.setString(3, e.getLevel());
                ps.setString(4, e.getMessage());
                ps.setObject(5, convertTimestamp(e.getTs()));
            }

            @Override
            public int getBatchSize() {
                return validEvents.size();
            }
        });

        log.info("{} 서버로부터 로그 {}개 수신됨",
                serverName, validEvents.size());

        // 4. 에러 감지 및 알림 (단순 텍스트 전송)
        List<String> errorLogs = events.stream()
                .filter(e -> "ERROR".equalsIgnoreCase(e.getLevel()))
                .map(LogEventDto::getMessage)
                .toList();

        sendDiscordMessage(serverName, discordWebhookUrl, errorLogs);

        return new IngestResultDto(serverName, events.size(), "로그 수신 완료");
    }

    private void sendDiscordMessage(String serverName, String discordWebhookUrl, List<String> errorLogs) {
        if (!errorLogs.isEmpty()) {
            String firstError = errorLogs.getFirst();
            String shortError = firstError.length() > 200
                    ? firstError.substring(0, 200) + "..."
                    : firstError;
            String alertMsg = createDiscordMessage(shortError);

            // 사용자 토큰으로 디스코드 알림 발송
            discordNotificationService.sendErrorAlert(discordWebhookUrl, serverName, alertMsg);
        }
    }

    private String createDiscordMessage(String shortError) {
        return String.format("""
            📋 **내용 요약:**
            `%s`
            """, shortError
        );
    }

    /**
     * 로그 분석 (LLM 도구용)
     * DB에서 최근 로그를 조회하여 요약
     */
    public ErrorLogAnalysisDto analyzeErrorLogs(String name) {
        TargetServer server = getServerOrThrow(name);

        // 1. 가장 최근 로그 100개 가져오기
        List<ServerLog> recentLogs = serverLogRepository.findTop100ByServerOrderByOccurredAtDesc(server);

        // 2. 최근 로그 100개를 역순으로 뒤집어 사건의 순서대로 보여줌.
        Collections.reverse(recentLogs);

        if (recentLogs.isEmpty()) {
            return new ErrorLogAnalysisDto(name, List.of(), 0, "✅ 수집된 로그가 없습니다.");
        }

        List<String> errors = new ArrayList<>();
        String lastMsg = "";
        int duplicateCount = 0;

        for (ServerLog log : recentLogs) {
            String msg = safe(log.getMessage());
            String level = safe(log.getLevel());

            // 에러가 아니면 패스 (단, Exception 힌트가 있으면 포함)
            if (!"ERROR".equalsIgnoreCase(level) && !containsExceptionHint(msg)) {
                continue;
            }

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
            String displayMsg = msg;
            if (displayMsg.length() > 500) {
                displayMsg = displayMsg.substring(0, 500) + "\n   ... (생략됨) ...";
            }

            errors.add(log.getOccurredAt() + " [" + level + "] " + displayMsg);
            lastMsg = msg;
        }

        if (duplicateCount > 0) {
            errors.add("   ㄴ (위와 동일한 에러가 " + duplicateCount + "번 더 반복되었습니다.)");
        }

        if (errors.isEmpty()) {
            return new ErrorLogAnalysisDto(name, List.of(), 0, "✅ 최근 구간에서 에러가 없습니다.");
        }

        return new ErrorLogAnalysisDto(
                name,
                errors,
                errors.size(),
                "⚠️ 최근 에러 로그가 발견되었습니다."
        );
    }

    // --- 유틸리티 메서드 ---

    // Timestamp(Long) -> LocalDateTime 변환
    private LocalDateTime convertTimestamp(Long ts) {
        if (ts == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }

    private boolean containsExceptionHint(String msg) {
        if (msg == null) return false;
        return msg.contains("Exception") || msg.contains("ERROR") || msg.contains("Caused by");
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