package com.kakao.kakao_test.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "log_alert_delivery")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogAlertDelivery {
    @Id
    @Column(length = 36)
    private String id;
    private Long serverId;
    private Long sentAt;

    public LogAlertDelivery(String id, long serverId) {
        this.id = id;
        this.serverId = serverId;
    }
}
