# 🩺 Server Doctor: Spring Boot Server Diagnosis MCP

> **LLM을 통한 안전하고 간편한 Spring Boot 서버 진단 및 모니터링 도구** > "복잡한 설정(Datadog, Prometheus) 없이, `docker-compose, application.yml, .env` 설정을 통해 내 서버의 상태를 LLM에게 물어보세요."

[![Tech Stack](https://img.shields.io/badge/Stack-Spring%20Boot%20%7C%20Python%20%7C%20Docker-blue)](https://github.com/)
[![MCP](https://img.shields.io/badge/Protocol-MCP-green)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

### 자세한 설명 블로그

[Spring 서버 진단 MCP 개발](https://velog.io/@takch02/series/MCP)


## 📖 프로젝트 소개 (Overview)

**Server Doctor**는 **MCP(Model Context Protocol)** 를 활용하여, LLM(ChatGPT, Claude 등)이 개발자의 로컬 또는 배포된 **Spring Boot 서버의 상태(Health)와 에러 로그(Logs)** 를 실시간으로 분석하고 진단해주는 도구입니다.

기존 모니터링 도구(Datadog, ELK)의 높은 비용과 복잡한 설정 문제를 해결하기 위해, **초기 스타트업 및 사이드 프로젝트**에 최적화된 **MVP** 형태의 진단 솔루션을 개발했습니다.

### 🎯 핵심 목표
* **Zero-Config:** `docker-compose.yml, application.yml, .env` 파일 수정으로 즉시 적용 가능.
* **Security First:** 외부 포트 노출 없이, **Sidecar Pattern**을 통해 내부망에서 안전하게 데이터를 수집.
* **AI Diagnosis:** 단순 로그 수집을 넘어, LLM이 문맥을 파악하여 에러 원인을 설명.

---
## 시연
**서버를 직접 등록하기 전에 데모서버를 시연하며 어떤 MCP인지 테스트 가능합니다.**

1. 데모 서버의 상태를 확인
<img width="997" height="691" alt="image" src="https://github.com/user-attachments/assets/984c316b-542a-4fc5-a7e2-228d6519f982" />

2. 에러 여부 확인
<img width="997" height="328" alt="image" src="https://github.com/user-attachments/assets/f46b5d4c-223c-4a74-b933-9a47163ddc40" />

3. 에러 분석
<img width="997" height="580" alt="image" src="https://github.com/user-attachments/assets/ea1656e6-7d62-4ff9-8220-8cc2b4dba283" />

## 사용자 서버 등록 과정

1. Application.yml
```java
logging:
  file:
    name: /app/logs/application.log

management:
  server:
    port: 9090
  endpoints:
    web:
      exposure:
        include: "health,metrics"
  endpoint:
    health:
      show-details: always
```
2. build.gradle 의존성 추가
```java
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

3. .env 추가
```
SERVER_NAME=test003
INGEST_TOKEN=c13600a7-0f90-4373-879a-b62c3d1389da // 서버 등록 시 생성되는 Token
MCP_DOMAIN=http://168.107.53.175
FORWARDER_IMAGE=ghcr.io/takch02/mcp-forwarder:latest
```

4. docker-compose.yml 추가
```
services:
  target:
    container_name: my-app-target
    image: my-app-image:latest
    volumes:
      - logs:/app/logs
    ports:
      - "8080:8080"

  forwarder:
    image: ${FORWARDER_IMAGE}
    container_name: mcp-forwarder
    depends_on: [target]
    volumes:
      - logs:/logs:ro
    environment:
      MCP_LOG_INGEST_URL: "${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/logs"
      MCP_METRIC_INGEST_URL: "${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/metrics"
      MCP_HEALTH_INGEST_URL: "${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/health"
      HEALTH_URL: "http://target:9090/actuator/health"
      MCP_TOKEN: "${INGEST_TOKEN}"
      LOG_PATH: "/logs/application.log"
      ACTUATOR_URL: "http://target:9090/actuator/metrics"
    restart: unless-stopped

volumes:
  logs:
```

## 🏗️ 아키텍처 (Architecture)

본 프로젝트는 보안과 확장성을 위해 **사이드카 패턴(Sidecar Pattern)** 을 채택했습니다.
<img width="1248" height="490" alt="스크린샷 2026-03-18 오전 10 50 54" src="https://github.com/user-attachments/assets/23171329-4b0e-4cd3-8f11-8527d285842b" />



### 🔄 작동 원리 (Forwarder System)
1.  **User Target (Spring Boot):** 사용자의 애플리케이션입니다. 로그 파일만 생성하며 외부로 데이터를 보내지 않습니다.
2.  **Forwarder (Sidecar Container):** 사용자의 서버와 동일한 Docker Network 내에서 실행되는 Python 에이전트입니다.
    * `Logs`: 공유 볼륨(Volume)을 통해 로그 파일을 실시간으로 읽습니다 (Tailing).
    * `Metrics`: 내부망(`http://target:9090`)을 통해 Actuator 정보에 접근합니다.
    * [Forwarder Github 링크](https://github.com/Takch02/server_monitoring_MCP_forwarder)
    <img width="512" height="780" alt="ChatGPT Image 2026년 1월 25일 오후 04_04_57" src="https://github.com/user-attachments/assets/9e8f5279-9e1a-4991-91c3-c61d1370591c" />

3.  **MCP Server:** Forwarder로부터 수집된 데이터를 받아 LLM에게 표준화된 MCP 프로토콜로 전달합니다.

---

## 🔒 보안 설계 (Security)

서버 진단 도구인 만큼 **민감 정보 보호**를 최우선으로 설계했습니다.

### 1. 네트워크 격리 (Network Isolation)
* **Actuator 포트(9090) 차단:** Spring Actuator 정보는 민감할 수 있습니다. 본 시스템은 **9090 포트를 외부에 절대 노출하지 않습니다.**
* 오직 Docker 내부 네트워크에 존재하는 **Forwarder**만이 9090 포트에 접근하여 데이터를 수집하고, 안전한 채널을 통해 MCP 서버로 전송합니다.

### 2. 민감 정보 마스킹 (PII Redaction)
로그에 포함될 수 있는 API Key, JWT Token, Password 등의 민감 정보가 LLM으로 전송되는 것을 막기 위해, 전송 전 **정규식(Regex) 기반의 필터링**을 수행합니다.

```python
# 마스킹 로직 예시 (Forwarder)
REDACT_PATTERNS = [
    (re.compile(r"(Authorization:\s*Bearer\s+)[A-Za-z0-9\-\._~\+\/]+=*", re.IGNORECASE), r"\1[REDACTED]"),
    (re.compile(r"(\b(token|access_token|secret)\s*=\s*)[^\s&]+", re.IGNORECASE), r"\1[REDACTED]"),
    # ...
]
```

## 🔧 트러블슈팅 (Troubleshooting)

### 1. Queue와 Jitter를 활용한 로그 유실 및 연쇄 장애 방어

**Problem:** 통신 장애 시 무한 재시도로 두 가지 구조적 문제 발생
- **리소스 고갈:** 재시도가 집중되며 사용자 서버 메모리 점유 지속 증가, OOM 위험
- **로그 유실:** 재시도 처리에 묶여 신규 로그가 전송되지 못함

**Solution:**
- 재시도 간격에 Jitter를 부여해 타겟 서버의 부하 분산
- TTL 1분 적용으로 오래된 로그를 폐기해 연쇄 장애 차단
- 최대 300건 제한의 메모리 Queue를 두어 호스트 서버의 메모리 고갈 방어 및 신규 로그 저장

**Result:** 로그 수집 서버 중단 시 → 최대 30,000건 로그 Queue 보관 → 복구 후 전량 전송, **유실 0건 확인**

---

### 2. 멱등성 키와 DB 제약조건을 활용한 중복 검증 오버헤드 제거

**Problem:** 네트워크 재전송 시 로그 중복 발생, 무거운 텍스트 대조 비용 및 
`try-catch` 예외 처리 오버헤드 발생

**Solution:**
- 메타데이터(서버명, 시간, 메시지) 기반 SHA-1 멱등성 키 생성
- DB 레벨의 `UNIQUE 인덱스(O(logN))`와 `ON DUPLICATE KEY UPDATE` 활용

**Result:** 애플리케이션 Stack Trace 생성 오버헤드 없이 안전하게 중복 데이터 무시 
및 성능 최적화

---

### 3. Bulk Insert와 재시도 파이프라인으로 Deadlock 방어 및 TPS 68.9% 개선

**Problem:** 대량 재시도 시 단건 INSERT로 인한 네트워크 병목 및 
중복 키 유입 시 `S-Lock → X-Lock` 승격으로 인한 Deadlock 발생

**Solution:**
- `JdbcTemplate` 기반 Bulk Insert 도입 및 메모리 내 사전 정렬로 인덱스 Page Split 최소화
- 구조적 Deadlock을 애플리케이션 레벨에서 방어하기 위해 Spring `@Retryable` 적용

**Result:** *(로컬 환경, Connection Pool 30개 제한 기준)*

| 지표 | 개선 전 | 개선 후 | 개선율 |
|------|--------|--------|--------|
| DB 저장 응답 | 249.32ms | 86.45ms | 65.3% 개선 |
| 전체 TPS | 594.8 | 1,004.5 | 68.9% 향상 |
| Deadlock 에러율 | 1% | 0% | 100% 개선 |

