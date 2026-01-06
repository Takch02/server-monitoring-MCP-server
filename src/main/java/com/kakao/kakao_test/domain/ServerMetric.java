package com.kakao.kakao_test.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

// domain/ServerMetric.java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "server_metric", indexes = {
    @Index(name = "idx_metric_server_time", columnList = "server_id, capturedAt")
})
public class ServerMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", nullable = false)
    private TargetServer server;

    private Double cpuUsage;      // 0.0 ~ 100.0
    private Double memoryUsedMb;  // MB 단위 변환 저장 추천
    private Double memoryMaxMb;

    private LocalDateTime capturedAt;

    @Builder
    public ServerMetric(TargetServer server, Double cpuUsage, Double memoryUsedMb, Double memoryMaxMb, LocalDateTime capturedAt) {
        this.server = server;
        this.cpuUsage = cpuUsage;
        this.memoryUsedMb = memoryUsedMb;
        this.memoryMaxMb = memoryMaxMb;
        this.capturedAt = capturedAt;
    }

    public static ServerMetric createMetric(Long ts, TargetServer server,
                                            Double cpuPercent, Double memUsedMb, Double memMaxMb) {
        return ServerMetric.builder()
                .server(server)
                .cpuUsage(cpuPercent)
                .memoryUsedMb(memUsedMb)
                .memoryMaxMb(memMaxMb)
                .capturedAt(convertTimestamp(ts))
                .build();
    }
    private static LocalDateTime convertTimestamp(Long ts) {
        if (ts == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
    }
}