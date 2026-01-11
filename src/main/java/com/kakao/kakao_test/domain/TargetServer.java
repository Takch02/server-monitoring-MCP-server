package com.kakao.kakao_test.domain;

import com.kakao.kakao_test.dto.RegisterServerRequest;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 서버 URL, Token 를 저장
 */
@Entity
@NoArgsConstructor
@Getter
@Table(name = "target_server")
public class TargetServer extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String serverName;

    @Column(unique = true, nullable = false)
    private String serverUrl;              // ngrok 등으로 변경 가능하므로 volatile

    private String healthPath;

    @Column(unique = true, nullable = false)
    private String mcpToken;         // 로그 수신 인증용(데모)

    private LocalDateTime heartBeat;

    @Builder
    public TargetServer(String serverName, String serverUrl, String healthPath, String mcpToken) {
        this.serverName = serverName;
        this.serverUrl = normalizeBaseUrl(serverUrl);
        this.healthPath = (healthPath == null || healthPath.isBlank()) ? "/actuator/health" : healthPath;
        this.mcpToken = mcpToken;
        this.heartBeat = LocalDateTime.now();
    }


    public void updateUrl(String newUrl) {
        this.serverUrl = normalizeBaseUrl(newUrl);
    }

    private String normalizeBaseUrl(String u) {
        if (u == null) return "";
        // 끝 슬래시 제거
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public static TargetServer register(RegisterServerRequest req, String token) {
        return TargetServer.builder()
                .serverName(req.getServerName())
                .mcpToken(token) // 토큰 저장
                .healthPath(req.getHealthPath())
                .serverUrl(req.getUrl())
                .build();
    }

    public void updateHeartbeat() {
        this.heartBeat = LocalDateTime.now();
    }
}
