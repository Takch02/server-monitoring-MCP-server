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
import java.util.Optional;

public interface ServerLogRepository extends JpaRepository<ServerLog, Long> {
    @Modifying
    @Query(value = """
            UPDATE server_log SET alert_delivery=:delivery WHERE server_id=:serverId AND alert_delivery='PENDING'
            ORDER BY id LIMIT 1
            """, nativeQuery = true)
    int assignFirstPending(String delivery, long serverId);

    @Modifying
    @Query("""
            update ServerLog l set l.alertDelivery=:delivery
            where l.server.id=:serverId and l.alertDelivery='PENDING'
            """)
    int assignAllPending(String delivery, long serverId);

    interface AlertGroup {
        String getGroupKey();
        String getSample();
        long getOccurrences();
    }

    // 그룹별 최초 행을 조인해 LONGTEXT 원문에서 앞 2000자만 읽는다.
    @Query(value = """
            SELECT g.group_key AS groupKey, LEFT(r.message, 2000) AS sample, g.n AS occurrences FROM (
                SELECT error_group_key AS group_key, MIN(id) AS sample_id, COUNT(*) AS n FROM server_log
                WHERE server_id=:serverId AND alert_delivery=:delivery GROUP BY error_group_key
            ) g JOIN server_log r ON r.id=g.sample_id ORDER BY g.n DESC, g.group_key
            """, nativeQuery = true)
    List<AlertGroup> topAlertGroups(long serverId, String delivery, Pageable limit);

    Optional<ServerLog> findFirstByServer_IdAndAlertDeliveryOrderById(long serverId, String alertDelivery);

    long countByServer_IdAndAlertDelivery(long serverId, String alertDelivery);

    interface ErrorGroupCount {
        String getGroupKey();
        long getOccurrences();
    }

    @Query("""
        select l.errorGroupKey as groupKey, count(l) as occurrences
        from ServerLog l where l.server = :server and l.errorGroupKey is not null
        and l.createdAt >= :start and l.createdAt < :end
        group by l.errorGroupKey order by count(l) desc, l.errorGroupKey
        """)
    List<ErrorGroupCount> countErrorGroups(@Param("server") TargetServer server,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

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