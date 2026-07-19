package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.domain.TargetServer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ServerLogRepository extends JpaRepository<ServerLog, Long> {
    // 특정 서버의 최신 로그 100개 가져오기
    List<ServerLog> findTop100ByServerOrderByOccurredAtDesc(TargetServer server);

    // 시간 창 내 최신 로그 101개 (101번째가 있으면 상한 도달 표기용)
    List<ServerLog> findTop101ByServerAndOccurredAtAfterOrderByOccurredAtDesc(TargetServer server, LocalDateTime since);

    // 시간 창 내 ERROR 로그 101건 (idx_log_level 인덱스 사용)
    List<ServerLog> findTop101ByServerAndLevelAndOccurredAtAfterOrderByOccurredAtDesc(
            TargetServer server, String level, LocalDateTime since);
    
    // 특정 서버의 에러 로그만 최신순 조회
    List<ServerLog> findByServerAndLevelOrderByOccurredAtDesc(TargetServer server, String level, Pageable pageable);

    @Modifying
    @Query(value = """
        INSERT INTO server_log (event_id, server_id, level, message, created_at, occurred_at)
        VALUES (:eventId, :serverId, :level, :message, NOW(), :occurredAt)
        ON DUPLICATE KEY UPDATE event_id = event_id
        """, nativeQuery = true)
    void insertIgnoreDuplicate(
            @Param("eventId") String eventId,
            @Param("serverId") Long serverId,
            @Param("level") String level,
            @Param("message") String message,
            @Param("occurredAt") LocalDateTime occurredAt
    );
}