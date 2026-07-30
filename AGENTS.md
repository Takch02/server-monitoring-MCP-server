# AGENTS.md

이 파일은 Codex(AI)가 이 저장소에서 작업할 때 매번 참조하는 **프로젝트 규칙**이다.
사람 협업 인원은 1명(오너)이며, AI가 기능 추가·버그 수정·리팩터링을 함께 수행한다.

세부 규칙은 `docs/agent/`에 주제별로 분리되어 있다. **아래 "상황별 참조 문서" 표에서 지금 하려는
작업에 해당하는 항목을 찾으면, 그 문서를 실제로 읽고 나서 작업을 진행할 것.** 이 문서들은 자동으로
불러와지지 않으므로, 트리거에 해당하는데 건너뛰면 규칙을 놓친다.

---

## 1. 프로젝트 개요

**Server Doctor** — LLM이 MCP(Model Context Protocol)를 통해 Spring Boot 서버의
`log / metrics / health`를 진단하는 MCP 서버.

- 이 저장소 = **MCP 수집·조회 서버** (Spring Boot).
- **Forwarder**(Python, Docker sidecar, 인메모리 큐에 로그 저장 후 전송)가 사용자 서버 내부망에서 데이터를 수집해
  이 서버의 `ingest` 엔드포인트로 **push** 한다. → 이 서버는 데이터를 받아 저장하고, `@McpTool`로 LLM에 노출.
- 데이터 흐름: `User App → (log파일/actuator) → Forwarder → [POST /api/servers/{name}/ingest/*] → MySQL → @McpTool → LLM`

### 스택
- Java 21 / Spring Boot 3.4.2 / Gradle
- Spring AI MCP (`spring-ai-starter-mcp-server-webmvc`, BOM 1.1.2)
- Spring Data JPA + MySQL, Actuator, Spring Retry, AOP, Lombok, springdoc-openapi

---

## 2. 아키텍처 & 레이어 규칙

패키지: `com.kakao.kakao_test`

| 레이어 | 패키지 | 역할 | 규칙 |
|--------|--------|------|------|
| MCP Tool | `mcp` | `@McpTool`로 LLM에 기능 노출 | **비즈니스 로직 금지**. service 호출 후 문자열 포맷만. |
| Controller | `controller` | HTTP 엔드포인트 (ingest / register 등) | 얇게 유지. 검증·로직은 service로 위임. |
| Service | `service` | 비즈니스 로직 · 트랜잭션 경계 | `@Transactional`은 여기에. |
| Repository | `repository` | Spring Data JPA 인터페이스 | 쿼리 메서드 / `@Query`. |
| Domain | `domain` | JPA 엔티티 | `BaseTimeEntity` 상속으로 생성/수정 시각 관리. |
| DTO | `dto` | 요청/응답/내부 전달 객체 | **가급적 `record`** 사용. |
| Config | `config` | 스프링 설정 | |
| Exception | `exception` | 커스텀 예외 + `GlobalExceptionHandler` | |

**의존 방향**: `mcp / controller → service → repository → domain`. 역방향 금지. 레이어를 건너뛰지 말 것.

---

## 3. 상황별 참조 문서 (반드시 트리거 시점에 읽을 것)

| 상황 (트리거) | 문서 | 왜 읽어야 하는가 |
|------|------|------|
| 코드를 작성/수정한다 (Service, DTO, 로깅, 예외 등) | [docs/agent/conventions.md](docs/agent/conventions.md) | 기존 코드 스타일과 다르게 짜면 리뷰에서 반려됨 |
| 새 진단 도구(`@McpTool`)를 추가하거나 수정한다 | [docs/agent/mcp-tool-workflow.md](docs/agent/mcp-tool-workflow.md) | service→mcp 순서, 네이밍, LLM용 description 작성법이 정해져 있음 |
| service 로직을 새로 만들거나 버그를 고친다 | [docs/agent/testing.md](docs/agent/testing.md) | 단위 테스트 필수 + 이 프로젝트만의 테스트 스타일 |
| 구현이 끝나서 커밋/PR로 넘어가려 한다 | [docs/agent/verification.md](docs/agent/verification.md) | code-review/security-review/verify-and-ci 적용 시점과 순서 |
| `git commit`을 실행하려 한다 (매번) | [docs/agent/commit.md](docs/agent/commit.md) | 커밋 메시지 형식 + **논리적 변경 하나당 커밋 하나** 원칙 |
| 시크릿/CI/Actuator/배포 워크플로우를 건드린다 | [docs/agent/prohibited.md](docs/agent/prohibited.md) | 절대 하면 안 되는 것 목록 |

---

## 4. 자주 쓰는 명령

```bash
./gradlew test          # 전체 테스트
./gradlew build         # 빌드(테스트 포함)
./gradlew bootRun       # 로컬 실행
gh run list --limit 5   # 최근 CI 결과
```
