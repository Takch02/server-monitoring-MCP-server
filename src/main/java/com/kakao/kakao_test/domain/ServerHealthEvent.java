package com.kakao.kakao_test.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "server_health_event",
       indexes = {
           @Index(name = "idx_health_server_ts", columnList = "server_name, ts")
       })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerHealthEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_name", nullable = false)
    private String serverName;

    @Column(nullable = false)
    private long ts;

    @Column(nullable = false)
    private String status;      // UP/DOWN/...

    @Column(nullable = false)
    private long latencyMs;

    @Column(nullable = false)
    private int httpStatus;

    @Column(length = 500)
    private String message;
}
