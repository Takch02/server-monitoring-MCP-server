package com.kakao.kakao_test.domain;

import com.kakao.kakao_test.dto.RegisterServerRequest;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 서버 URL, Token 를 저장
 */
@Entity
@NoArgsConstructor
@Getter
@Setter
@Table(name = "target_server")
public class TargetServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String serverName;

    @Column(unique = true, nullable = false)
    private String mcpToken;         // 로그 수신 인증용

    private LocalDateTime heartBeat;  // 최근 수신받은 시간

    private String lastHealthStatus;   // UP/DOWN/UNKNOWN

    private Integer lastHealthHttpStatus;    // 200/503/0

    private Long lastHealthLatencyMs;        // ms

    public void updateHealthSnapshot(String newStatus, long latencyMs, int status) {
        this.lastHealthStatus = newStatus;
        this.lastHealthHttpStatus = status;
        this.lastHealthLatencyMs = latencyMs;
        this.heartBeat = LocalDateTime.now();
    }

    @Builder
    public TargetServer(String serverName, String mcpToken) {
        this.serverName = serverName;
        this.mcpToken = mcpToken;
        this.heartBeat = LocalDateTime.now();
    }

    public static TargetServer register(RegisterServerRequest req, String token) {
        return TargetServer.builder()
                .serverName(req.getServerName())
                .mcpToken(token) // 토큰 저장
                .build();
    }
}
