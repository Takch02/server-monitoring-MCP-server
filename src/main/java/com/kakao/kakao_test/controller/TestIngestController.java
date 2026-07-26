package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.LogEventDto;
import com.kakao.kakao_test.repository.ServerLogRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Profile("test")
@RestController
@RequiredArgsConstructor
@RequestMapping("/test")
public class TestIngestController {

    private final JdbcTemplate jdbcTemplate;
    private final ServerLogRepository serverLogRepository;
    private final TargetServerRepository targetServerRepository;

    @Transactional
    @PostMapping("/ingest/logs/save-all/{serverId}")
    public Map<String, Object> saveAll(@PathVariable Long serverId,
                                       @RequestBody List<LogEventDto> events) {
        if (events == null || events.isEmpty()) {
            return Map.of("inserted", 0);
        }

        TargetServer server = targetServerRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("서버 없음: " + serverId));

        List<ServerLog> logs = events.stream()
                .map(e -> ServerLog.builder()
                        .server(server)
                        .eventId(e.getEventId())
                        .level(e.getLevel())
                        .message(e.getMessage())
                        .occurredAt(toLocalDateTime(e.getTs()))
                        .build())
                .toList();

        serverLogRepository.saveAll(logs);

        return Map.of("inserted", logs.size());
    }

    @PostMapping("/ingest/logs/batch/{serverId}")
    public Map<String, Object> batchUpdate(@PathVariable Long serverId,
                                           @RequestBody List<LogEventDto> events) {
        if (events == null || events.isEmpty()) {
            return Map.of("inserted", 0);
        }

        String sql = """
                INSERT IGNORE INTO server_log (event_id, server_id, level, message, created_at, occurred_at)
                VALUES (?, ?, ?, ?, NOW(), ?)
                """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LogEventDto e = events.get(i);
                ps.setString(1, e.getEventId());
                ps.setLong(2, serverId);
                ps.setString(3, e.getLevel());
                ps.setString(4, e.getMessage());
                ps.setObject(5, toLocalDateTime(e.getTs()));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });

        return Map.of("inserted", events.size());
    }

    private LocalDateTime toLocalDateTime(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }
}
