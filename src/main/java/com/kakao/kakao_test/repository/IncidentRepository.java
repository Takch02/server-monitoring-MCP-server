package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.Incident;
import com.kakao.kakao_test.domain.ServerLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, String> {
    @Query("select local datetime from ServerLog l where l.id=:logId")
    LocalDateTime currentDatabaseTime(long logId);

    // 커밋된 행 ID를 보관해 이후 같은 시각을 가진 로그가 와도 사건 범위가 달라지지 않는다.
    @Modifying
    @Query(value = """
            INSERT INTO incident_occurrence (incident_id, log_id)
            SELECT :id, id FROM server_log
            WHERE server_id=:serverId AND error_group_key=:groupKey AND created_at BETWEEN :start AND :end
            """, nativeQuery = true)
    void snapshotOccurrences(String id, long serverId, String groupKey, LocalDateTime start, LocalDateTime end);

    @Query(value = "SELECT COUNT(*) FROM incident_occurrence WHERE incident_id=:incidentId", nativeQuery = true)
    long countOccurrences(String incidentId);

    @Query(value = """
            SELECT l.* FROM incident_occurrence o JOIN server_log l ON l.id=o.log_id
            WHERE o.incident_id=:incidentId
            ORDER BY l.occurred_at DESC, l.id DESC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<ServerLog> findOccurrences(String incidentId, int size, long offset);
}
