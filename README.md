# 🩺 Server Doctor: Spring Boot Server Diagnosis MCP

> **LLM을 통한 안전하고 간편한 Spring Boot 서버 진단 및 모니터링 도구** > "복잡한 설정(Datadog, Prometheus) 없이, `docker-compose, application.yml, .env` 설정을 통해 내 서버의 상태를 LLM에게 물어보세요."

[![Tech Stack](https://img.shields.io/badge/Stack-Spring%20Boot%20%7C%20Python%20%7C%20Docker-blue)](https://github.com/)
[![MCP](https://img.shields.io/badge/Protocol-MCP-green)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

### 자세한 설명 블로그

[Spring 서버 진단 MCP 개발(Forwarder 편)](https://velog.io/@takch02/MCP-Spring-서버-진단-MCP-개발완료)


## 📖 프로젝트 소개 (Overview)

**Server Doctor**는 **MCP(Model Context Protocol)** 를 활용하여, LLM(ChatGPT, Claude 등)이 개발자의 로컬 또는 배포된 **Spring Boot 서버의 상태(Health)와 에러 로그(Logs)** 를 실시간으로 분석하고 진단해주는 도구입니다.

기존 모니터링 도구(Datadog, ELK)의 높은 비용과 복잡한 설정 문제를 해결하기 위해, **초기 스타트업 및 사이드 프로젝트**에 최적화된 **MVP(Minimum Viable Product)** 형태의 진단 솔루션을 개발했습니다.

### 🎯 핵심 목표
* **Zero-Config:** `docker-compose.yml, application.yml, .env` 파일 수정으로 즉시 적용 가능.
* **Security First:** 외부 포트 노출 없이, **Sidecar Pattern**을 통해 내부망에서 안전하게 데이터를 수집.
* **AI Diagnosis:** 단순 로그 수집을 넘어, LLM이 문맥을 파악하여 에러 원인을 설명.

---

## 🏗️ 아키텍처 (Architecture)

본 프로젝트는 보안과 확장성을 위해 **사이드카 패턴(Sidecar Pattern)** 을 채택했습니다.

<img width="1024" height="1536" alt="ChatGPT Image 2026년 1월 24일 오후 11_11_12" src="https://github.com/user-attachments/assets/4201c7c3-7fad-474e-be69-fab5369f43d3" />


### 🔄 작동 원리 (Forwarder System)
1.  **User Target (Spring Boot):** 사용자의 애플리케이션입니다. 로그 파일만 생성하며 외부로 데이터를 보내지 않습니다.
2.  **Forwarder (Sidecar Container):** 사용자의 서버와 동일한 Docker Network 내에서 실행되는 Python 에이전트입니다.
    * `Logs`: 공유 볼륨(Volume)을 통해 로그 파일을 실시간으로 읽습니다 (Tailing).
    * `Metrics`: 내부망(`http://target:9090`)을 통해 Actuator 정보에 접근합니다.
    <img width="1024" height="1536" alt="ChatGPT Image 2026년 1월 25일 오후 04_04_57" src="https://github.com/user-attachments/assets/9e8f5279-9e1a-4991-91c3-c61d1370591c" />

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
