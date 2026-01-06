package com.kakao.kakao_test.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// domain/ServerLog.java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "server_log", indexes = {
    @Index(name = "idx_log_server_time", columnList = "server_id, occurredAt"), // 시간순 조회용
    @Index(name = "idx_log_level", columnList = "server_id, level, occurredAt") // 에러만 필터링용
})
public class ServerLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private TargetServer server;

    private String level; // INFO, WARN, ERROR

    // 로그 내용은 매우 길 수 있음 (TEXT 타입 사용)
    @Lob 
    @Column(columnDefinition = "LONGTEXT") 
    private String message;

    private LocalDateTime occurredAt; // 로그 발생 시각 (수집 시각 X)

    @Builder
    public ServerLog(TargetServer server, String level, String message, LocalDateTime occurredAt) {
        this.server = server;
        this.level = level;
        this.message = message;
        this.occurredAt = occurredAt;
    }
}