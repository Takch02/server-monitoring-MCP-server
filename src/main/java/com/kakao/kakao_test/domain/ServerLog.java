package com.kakao.kakao_test.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 서버의 Log 를 저장하는 스키마
 * eventId 는 DB 설계 시 중복되면 안되는 값이므로 Unique Constraint로 지정(자동으로 Unique index를 만들며 중복 탐색 시 빠름)
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "server_log",
        uniqueConstraints = {
            @UniqueConstraint(name = "ux_server_log_eventid", columnNames = "event_id")
        },
        indexes = {
    @Index(name = "idx_log_server_time", columnList = "server_id, occurredAt"), // 시간순 조회용
    @Index(name = "idx_log_level", columnList = "server_id, level, occurredAt") // 에러만 필터링
})
public class ServerLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private TargetServer server;

    /**
     * Log 중복 저장을 막기위헤 SHA1 해시를 이용하여 생성
     * DB Insert 시 중복되면 자동으로 무시됨.
     */
    @Column(name="event_id", length = 40, nullable = false)
    private String eventId;

    private String level; // INFO, WARN, ERROR

    // 로그 내용은 매우 길 수 있음 (TEXT 타입 사용)
    @Lob 
    @Column(columnDefinition = "LONGTEXT") 
    private String message;

    private LocalDateTime createdAt;
    private LocalDateTime occurredAt; // 로그 발생 시각 (수집 시각 X)

    @Builder
    public ServerLog(TargetServer server, String eventId, String level, String message, LocalDateTime occurredAt) {
        this.server = server;
        this.eventId = eventId;
        this.level = level;
        this.message = message;
        this.occurredAt = occurredAt;
        this.createdAt = LocalDateTime.now();
    }
}