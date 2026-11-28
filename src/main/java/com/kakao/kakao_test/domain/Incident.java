package com.kakao.kakao_test.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident extends BaseTimeEntity {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id", nullable = false)
    private TargetServer server;

    @Column(length = 64, nullable = false)
    private String errorGroupKey;
    private long representativeLogId;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private long expiresAt;
    private boolean deliveryScoped;

    public Incident(String deliveryId, ServerLog representative, LocalDateTime windowEnd, long expiresAt,
                    boolean deliveryScoped) {
        id = deliveryId;
        server = representative.getServer();
        errorGroupKey = representative.getErrorGroupKey();
        representativeLogId = representative.getId();
        windowStart = representative.getCreatedAt();
        this.windowEnd = windowEnd;
        this.expiresAt = expiresAt;
        this.deliveryScoped = deliveryScoped;
    }

    public void renewLink(long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
