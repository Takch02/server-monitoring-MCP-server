package com.kakao.kakao_test.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "log_alert_channel")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogAlertChannel extends BaseTimeEntity {
    @Id
    @Column(name = "server_id")
    private Long serverId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", insertable = false, updatable = false)
    private TargetServer server;

    @Column(nullable = false, columnDefinition = "CHAR(64)")
    private String webhookHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedWebhook;

    private Long lastSentAt;
    private long availableAt;

    @Column(length = 36)
    private String activeDelivery;

    @Column(length = 36)
    private String leaseToken;

    public void reserve(String deliveryId, String token, long until) {
        activeDelivery = deliveryId;
        leaseToken = token;
        availableAt = until;
    }
}
