package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.LogAlertChannel;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface LogAlertRepository extends JpaRepository<LogAlertChannel, Long> {
    boolean existsByServerIdAndWebhookHash(long serverId, String webhookHash);

    // 동시 최초 등록을 허용하고, 목적지 교체 시에는 진행 중인 발송 상태를 보존한다.
    @Modifying
    @Query(value = """
            INSERT INTO log_alert_channel (server_id, webhook_hash, encrypted_webhook, created_at, updated_at)
            VALUES (:serverId, :hash, :stored, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE webhook_hash=:hash, encrypted_webhook=:stored, updated_at=CURRENT_TIMESTAMP(6)
            """, nativeQuery = true)
    void saveDestination(long serverId, String hash, String stored);

    @Query("""
            select c.serverId from LogAlertChannel c where c.availableAt <= :now
            and (c.activeDelivery is not null or exists (
                select l.id from ServerLog l where l.server.id = c.serverId and l.alertDelivery = 'PENDING'))
            order by c.availableAt, c.serverId
            """)
    List<Long> readyServers(long now, Pageable limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LogAlertChannel c where c.serverId = :serverId")
    LogAlertChannel lockChannel(long serverId);

    @Query(value = "select CAST(CURRENT_TIMESTAMP(0) AS DATETIME)", nativeQuery = true)
    LocalDateTime currentLogReceiptTime();

    @Modifying
    @Query("""
            update LogAlertChannel c set c.lastSentAt=:now, c.availableAt=:next,
                c.leaseToken=null, c.activeDelivery=null, c.updatedAt=local datetime
            where c.serverId=:serverId and c.leaseToken=:token
            """)
    int releaseAfterSuccess(long serverId, String token, long now, long next);

    @Modifying
    @Query("""
            update LogAlertChannel c set c.availableAt=:next, c.leaseToken=null, c.updatedAt=local datetime
            where c.serverId=:serverId and c.leaseToken=:token
            """)
    void releaseForRetry(long serverId, String token, long next);
}
