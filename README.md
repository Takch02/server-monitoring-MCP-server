# 🩺 Server Doctor: Spring Boot Server Diagnosis MCP

> **LLM을 통한 안전하고 간편한 Spring Boot 서버 진단 및 모니터링 도구**
> "복잡한 설정(Datadog, Prometheus) 없이, `docker-compose, application.yml, .env` 설정을 통해 내 서버의 상태를 LLM에게 물어보세요."

[![Tech Stack](https://img.shields.io/badge/Stack-Spring%20Boot%20%7C%20Python%20%7C%20Docker-blue)](https://github.com/)
[![MCP](https://img.shields.io/badge/Protocol-MCP-green)](https://modelcontextprotocol.io/)
[![Kakao PlayMCP](https://img.shields.io/badge/Kakao%20PlayMCP-공식%20승인·배포-FEE500)](https://playmcp.kakao.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

> 🎉 **카카오 PlayMCP 공식 승인 · 배포 완료**

### 자세한 설명 블로그

[Spring 서버 진단 MCP 개발](https://velog.io/@takch02/series/MCP)


## 📖 프로젝트 소개 (Overview)

**Server Doctor**는 **MCP(Model Context Protocol)** 를 활용하여, LLM(ChatGPT, Claude 등)이 개발자의 로컬 또는 배포된 **Spring Boot 서버의 상태(Health)와 에러 로그(Logs)** 를 실시간으로 분석하고 진단해주는 도구입니다.

기존 모니터링 도구(Datadog, ELK)의 높은 비용과 복잡한 설정 문제를 해결하기 위해, **초기 스타트업 및 사이드 프로젝트**에 최적화된 **MVP** 형태의 진단 솔루션을 개발했습니다.

- 기존: 서버 접속 → 로그 조회 → 복사 → LLM 프롬프트 입력
- 개선: **LLM 진단 요청 → MCP Tool 자동 호출 → 분석 결과 반환**

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
<img width="1248" height="490" alt="스크린샷 2026-03-18 오전 10 50 54" src="https://github.com/user-attachments/assets/23171329-4b0e-4cd3-8f11-8527d285842b" />

### 🤔 설계 결정: 왜 Pull이 아닌 Push인가? (Pull → Push 전환)
* **기존 Pull 방식의 한계:** 수집 서버가 대상 서버의 Actuator 포트(9090)를 **외부에서 직접 호출**해야 해 외부 포트 개방이 필수였습니다. 구현은 단순하지만 모니터링 포트가 외부에 노출되는 보안 위험이 컸습니다.
* **Push 방식으로 전환:** 구조 복잡도를 감수하고, 사용자 서버 내부에 Forwarder를 **Sidecar로 배치해 수집을 내부에서 수행하고 결과만 Push**하도록 변경. 이를 통해 **모니터링 목적의 외부 접근 포트를 제거**(앱 서비스 포트는 별개 유지)하고, 설정 파일만으로 기존 서버와 독립적인 로그 수집 구조를 확보했습니다.

### 🔄 작동 원리 (Forwarder System)
1.  **User Target (Spring Boot):** 사용자의 애플리케이션입니다. 로그 파일만 생성하며 외부로 데이터를 보내지 않습니다.
2.  **Forwarder (Sidecar Container):** 사용자의 서버와 동일한 Docker Network 내에서 실행되는 Python 에이전트입니다.
    * `Logs`: 공유 볼륨(Volume)을 통해 로그 파일을 실시간으로 읽습니다 (Tailing).
    * `Metrics`: 내부망(`http://target:9090`)을 통해 Actuator 정보에 접근합니다.
    * [Forwarder Github 링크](https://github.com/Takch02/server_monitoring_MCP_forwarder)
    <img width="512" height="780" alt="ChatGPT Image 2026년 1월 25일 오후 04_04_57" src="https://github.com/user-attachments/assets/9e8f5279-9e1a-4991-91c3-c61d1370591c" />

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

### 1. Queue + Jitter — OOM 및 로그 유실 방어

**Problem:** 네트워크 오류 시 하나의 요청이 계속 재전송되면서 두 가지 구조적 문제가 발생.
- **리소스 고갈(OOM):** 새로 받아오는 로그가 크기 제한 없는 배열에 무한 누적되며 Heap 고갈 위험.
- **로그 유실:** 재시도 처리에 묶여 신규 로그가 전송되지 못함.

**Solution:**
- 재시도 간격을 고정하면 여러 요청이 동시에 재시도되며 부하가 한 번에 몰릴 수 있어, 간격에 **지수 Backoff + Jitter**를 부여해 분산.
- 큐를 무제한으로 두면 OOM이 재발하므로 **크기 제한 Queue(최대 300건) + TTL 1분**으로 오래된 로그를 폐기하는 백오프 적용.
- HTTP 상태코드 기반 재시도 정책: `2xx` 성공 처리, `4xx` Client Error는 재시도하지 않고 로그만 남김, `5xx` Server Error만 재시도하되 **총 재시도 시간 60s 초과 시 요청 폐기(REQUEST DELETED)**.

**Result:** 수집 서버 중단 시 → **1,000건 보관 → 복구 후 전량 전송, 유실 0건 확인**

---

### 2. Bulk Insert — 네트워크 왕복 횟수 감소의 민감도 실증

**Problem:** 클라이언트(Forwarder)가 100건씩 묶어 전송하는데, 서버에서 `saveAll()`로 저장 시 처리 병목 발생.

**Solution (진단 → 개선):**
- PK가 `IDENTITY(auto_increment)` 전략이라 JPA가 INSERT 직후 PK를 받아와야 해 **JDBC 배치가 원천 비활성화**되고, 100개 INSERT가 개별 statement로 순차 전송되며 **왕복이 건수만큼 누적**되는 것이 병목이라 진단.
- `SEQUENCE` 전략은 단일 로우 채번 락 경합으로 병목만 옮겨갈 뿐이라 배제.
- `JdbcTemplate.batchUpdate()` + `rewriteBatchedStatements=true`로 **multi-row INSERT를 재작성**해 왕복을 **100 → 1회**로 축소.

**Result:** *(K6 20 VU ramping, 요청당 100건 배치, ToxiProxy로 db–app 네트워크 레이턴시 주입)*

| 주입 지연 | saveAll | batchUpdate(false) | batchUpdate(true) | true 우위 |
|---|---|---|---|---|
| 0ms | 94.9 rps / p95 58ms | 98.9 rps / p95 41ms | **107.7 rps / p95 28ms** | +13.5% |
| 1ms | 46.4 rps / p95 203ms | 44.8 rps / p95 226ms | **105.6 rps / p95 29ms** | +127% |
| 5ms | 11.7 rps / p95 1,304ms | 10.3 rps / p95 1,486ms | **98.9 rps / p95 36ms** | +749% |

- 왕복 1회인 `batchUpdate(true)`는 5ms 지연에서도 **처리량 8%만 감소(p95 28→36ms)** 한 반면, 왕복 100회인 `saveAll`·`batchUpdate(false)`는 처리량이 약 **1/10로 붕괴**.
- batch 우위는 지연 0→5ms에서 **+13.5% → +749%로 단조 증가** → 병목의 본질이 "네트워크 왕복 횟수"임을 정량적으로 실증.

---

### 3. SHA-1 멱등성 키 — 중복 삽입 방지

**Problem:** 네트워크 재전송 시 동일 로그가 중복 발행됨. 텍스트 전체를 직접 대조하면 비교 비용이 크고, 애플리케이션 레벨 `try-catch` 예외 처리 오버헤드가 발생.

**Solution:**
- `serverName + timestamp + 메시지 512자` 기반 **SHA-1 키**를 Forwarder가 생성해 `eventId`로 전송.
- 수집 서버는 **UNIQUE 인덱스(O(logN)) + `ON DUPLICATE KEY UPDATE`** 로 중복 검증을 **DB에 위임**해 애플리케이션 Stack Trace 생성 오버헤드 제거.

**Result:** 동일 이벤트 재처리 테스트 기준 **중복 데이터 미발생 검증**
