package com.kakao.kakao_test.domain;

import com.kakao.kakao_test.dto.RegisterServerRequest;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    @Builder
    public TargetServer(String serverName, String mcpToken) {
        this.serverName = serverName;
        this.mcpToken = mcpToken;
    }

    public static TargetServer register(RegisterServerRequest req, String token) {
        return TargetServer.builder()
                .serverName(req.getServerName())
                .mcpToken(token) // 토큰 저장
                .build();
    }
}
