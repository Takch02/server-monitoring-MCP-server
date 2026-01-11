package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.RegisterServerRequest;
import com.kakao.kakao_test.dto.RegisterServerResponse;
import com.kakao.kakao_test.exception.DuplicationServer;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerRegisterService {

    private final TargetServerRepository targetServerRepository;

    @Value("${mcp.server-url}")
    private String mcpDomain;

    @Value("${forwarder.image-url}")
    private String forwarderDomain;
    /**
     * 서버 등록
     */
    @Transactional
    public RegisterServerResponse registerServer(RegisterServerRequest req) {
        // 서버 이름 중복 처리
        if (targetServerRepository.existsByServerName(req.getServerName())) {
            throw new IllegalArgumentException("이미 등록된 서버 이름입니다: " + req.getServerName());
        }
        duplicateServer(req.getServerName(), req.getUrl());
        // 토큰 생성
        String token = UUID.randomUUID().toString();

        TargetServer server = TargetServer.register(req, token);

        targetServerRepository.save(server);
        log.info("✅ 서버 등록 완료: {}", server.getServerName());

        return new RegisterServerResponse(
                server.getServerName(),
                req.getUrl(),
                req.getHealthPath(),
                token,
                generateSetupGuide(server.getServerName(), server.getMcpToken())
        );
    }

    /**
     * 서버 등록 전 "이름 + url" 이 중복되는지 탐색
     */
    private void duplicateServer(String serverName, String url) {
        if (targetServerRepository.findByServerNameAndServerUrl(serverName, url).isPresent()) {
            throw new DuplicationServer("이미 존재하는 서버입니다. 서버 이름 : " + serverName + ", url : " + url);
        }
    }

    /**
     * 서버 URL 수정
     */
    @Transactional
    public void updateServerUrl(String name, String url) {
        TargetServer server = targetServerRepository.getByServerName(name);
        server.updateUrl(url);
    }
    // 📋 마크다운 가이드 생성 메서드
    public String generateSetupGuide(String serverName, String token) {
        if (serverName == null || serverName.isEmpty()) {
            serverName = "서버이름";
        }
        if (token == null || token.isEmpty()) {
            token = "서버 등록 후 발급받은 토큰";
        }

        return String.format(""" 
        모니터링을 시작하기 위해 대상 서버에 아래 3단계 설정을 적용해주세요.
        
        ---
        
        ### 1️⃣ Spring Boot 설정 (`application.yml`)
        로그가 파일로 남고, Actuator가 9090 포트로 열리도록 설정합니다.
        
        ```yaml
        # 로그 경로 설정 (컨테이너 내부 경로)
        logging:
          file:
            name: /app/logs/application.log
        
        # Actuator 포트 분리 및 노출 설정
        management:
          server:
            port: 9090
          endpoints:
            web:
              exposure:
                include: "health,metrics,prometheus"
          endpoint:
            health:
              show-details: always
        ```
        
        ---
        
        ### 2️⃣ 환경 변수 설정 (`.env`)
        `docker-compose.yml`과 같은 위치에 `.env` 파일을 만들고 아래 내용을 붙여넣으세요.
        
        ```properties
        # MCP 설정
        SERVER_NAME=%s
        INGEST_TOKEN=%s
        MCP_DOMAIN=%s
        FORWARDER_IMAGE=%s
        
        # (선택) 알림 받을 디스코드 웹훅
        DISCORD_WEBHOOK_URL=디스코드 웹훅 URL
        ```
        
        ---
        
        ### 3️⃣ 실행 설정 (`docker-compose.yml`)
        기존 앱(`target`)과 수집기(`forwarder`)가 **로그 볼륨**을 공유해야 합니다.
        
        ```yaml
        version: '3.8'
        
        services:
          # 🟢 1. 사용자 앱 (target)
          target:
            container_name: my-app-target
            image: my-app-image:latest # 본인의 앱 이미지로 변경
            volumes:
              - logs:/app/logs # ⭐️ 로그 폴더 공유 필수!
            ports:
              - "8080:8080" # 서비스 포트
              # 9090 포트는 외부 노출 불필요 (forwarder가 내부망으로 접속)
        
          # 🟡 2. MCP 수집기 (forwarder)
          forwarder:
            image: ${FORWARDER_IMAGE}
            container_name: mcp-forwarder
            depends_on: [target]
            volumes:
              - logs:/logs:ro # target이 쓴 로그를 읽기 전용으로 마운트
            environment:
              MCP_LOG_INGEST_URL: "http://${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/logs"
              MCP_METRIC_INGEST_URL: "http://${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/metrics"
              MCP_TOKEN: "${INGEST_TOKEN}"
              DISCORD_WEBHOOK_URL: "${DISCORD_WEBHOOK_URL}"
              LOG_PATH: "/logs/application.log"
              # target 컨테이너의 9090 포트로 접속
              ACTUATOR_URL: "http://target:9090/actuator/metrics"
            restart: unless-stopped
        
        volumes:
          logs: # 로그 공유용 볼륨 정의
        ```
        
        🚀 **설정 후 `docker-compose up -d`로 실행하면 자동으로 수집이 시작됩니다!**
        """, serverName, token, mcpDomain, forwarderDomain);
    }
}
