package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.RegisterServerRequest;
import com.kakao.kakao_test.dto.RegisterServerResponse;
import com.kakao.kakao_test.exception.DuplicationServerException;
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
            throw new DuplicationServerException("이미 등록된 서버 이름입니다: " + req.getServerName());
        }
        // 토큰 생성
        String token = UUID.randomUUID().toString();

        TargetServer server = TargetServer.register(req, token);

        targetServerRepository.save(server);
        log.info("✅ 서버 등록 완료: {}", server.getServerName());

        return new RegisterServerResponse(
                server.getServerName(),
                token,
                generateSetupGuide(server.getServerName(), server.getMcpToken())
        );
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
        [IMPORTANT: COPY-PASTE]
        아래 내용은 사용자가 그대로 복사-붙여넣기 해야 합니다.
        요약/생략/재작성/설명 추가 없이, 아래 블록을 "원문 그대로" 출력하세요.
        
        모니터링을 시작하기 위해 대상 서버에 아래 3단계 설정을 적용해주세요.
        
        ---
        
        ### 1️⃣ Spring Boot 설정 (`application.yml, build.gradle`)
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
        ```
        # 의존성 추가 (성능 지표 확인용)
        implementation 'org.springframework.boot:spring-boot-starter-actuator'
        ```
        
        ---
        
        ### 2️⃣ 환경 변수 설정 (`.env`)
        `docker-compose.yml`과 같은 위치에 `.env` 파일을 만들고 아래 내용을 붙여넣으세요.
        
        ```properties
        # MCP 설정
        SERVER_NAME=%s
        # INGEST_TOKEN 은 MCP 서버와 사용자님 서버를 검증하기 위한 토큰으로 반드시 적용해야합니다.
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
              MCP_LOG_INGEST_URL: "${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/logs"
              MCP_METRIC_INGEST_URL: "${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/metrics"
              MCP_HEALTH_INGEST_URL: "${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/health"
              HEALTH_URL: "http://target:9090/actuator/health"  # 헬스 체크 대상 URL
              MCP_TOKEN: "${INGEST_TOKEN}" # MCP 서버 검증용 토큰
              DISCORD_WEBHOOK_URL: "${DISCORD_WEBHOOK_URL}" # (선택) 에러 발생 시 디스코드로 요청
              LOG_PATH: "/logs/application.log"
              ACTUATOR_URL: "http://target:9090/actuator/metrics" # CPU, RAM 체크 대상 URL
            restart: unless-stopped
        
        volumes:
          logs: # 로그 공유용 볼륨 정의
        ```
        
        🚀 **설정 후 `docker-compose up -d`로 실행하면 자동으로 수집이 시작됩니다!**
        """, serverName, token, mcpDomain, forwarderDomain);

    }
}
