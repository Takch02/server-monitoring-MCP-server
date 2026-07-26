# CLAUDE.md

이 파일은 Claude Code(AI)가 이 저장소에서 작업할 때 매번 참조하는 **프로젝트 규칙**이다.
사람 협업 인원은 1명(오너)이며, AI가 기능 추가·버그 수정·리팩터링을 함께 수행한다.

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

## 3. 코드 컨벤션 (기존 코드를 따를 것)

- **Service**: `@Slf4j @Service @RequiredArgsConstructor` + `private final` 생성자 주입. 필드 주입(`@Autowired`) 금지.
- **DTO**: `record`. (예: `HealthIngestDto`, `LogEventDto`)
- **로깅**: `log.info/warn/error`. 한글 메시지 OK. 민감정보(토큰/비밀번호) 로깅 금지.
- **주석**: 단계가 있는 로직은 기존처럼 `// 1) ... // 2) ...` 번호 주석 스타일.
- **상수**: 매직넘버는 `private static final`로 (예: `STALE_SECONDS = 60`).
- **예외**: 도메인 예외는 `exception` 패키지 커스텀 예외를 쓰고 `GlobalExceptionHandler`에서 처리.
- **MCP Tool 설명**: `@McpTool(description=...)`·`@McpToolParam(description=...)`은 **LLM이 읽는 프롬프트**다.
  한글로 명확히, 기본값·예시(예: `168 (7일)`)까지 적을 것. 여기가 곧 사용성.

---

## 4. MCP Tool 추가/수정 절차 (주요 반복 작업)

새 진단 도구를 추가할 때:
1. `service`에 로직 구현 (`~ForMcp` 형태의 문자열 반환 메서드가 관례) + **단위 테스트 작성**.
2. `mcp/ServerDoctorMcpTools.java`에 `@McpTool` 메서드 추가 — service 호출 + 결과 포맷만.
3. `name`은 `ServerDoctor-<snake_case>` 규칙. `description`/`@McpToolParam`을 LLM 관점에서 꼼꼼히.
4. 파라미터 기본값·널 처리는 tool 메서드에서 방어적으로 (기존 `sinceHours` 패턴 참고).
5. `./gradlew test` 통과 확인.

---

## 5. 테스트 규칙 (필수)

- **새 service 로직에는 단위 테스트가 필수다.** 버그 수정 시에는 회귀 방지 테스트를 우선 추가.
- 스타일: JUnit5 + `@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`,
  **AssertJ**(`assertThat`) + **BDDMockito**(`given`/`then`).
- 테스트 메서드명은 기존처럼 **한글**로 상황을 서술 (예: `잘못된토큰_SecurityException`).
- given/when/then 구조를 유지.

---

## 6. 검증 (단계별 skill 적용 — 필수)

구현이 끝났다고 바로 커밋/PR로 가지 말고, 아래 순서로 검증 skill을 적용한다.
1인 프로젝트라 "제3자 리뷰"가 없으므로, 이 단계들이 그 역할을 대신한다.

| 시점 | Skill | 목적 |
|------|-------|------|
| **구현 직후 (매번)** | `code-review` | 버그·재사용성·단순화·효율성 점검. 커밋 전 최소 1회는 필수. |
| **인증/토큰/외부노출/DB 접근 관련 변경 시** | `security-review` | MCP 토큰 검증, Actuator 9090 노출, PII(로그 내 시크릿) 마스킹 등 이 프로젝트 핵심 리스크 점검. |
| **PR 올리기 직전** | `verify-and-ci` | `./gradlew test` 전체 통과 + `.github/workflows/workflow.yml` 체크리스트(테스트 스킵, 시크릿 하드코딩, 헬스체크/롤백, 이미지 태그) 점검. |

- 커밋 자체는 hook이 `git commit`을 가로채 `./gradlew test`를 강제 실행하지만, 이는 **회귀 방지 최후 방어선**일 뿐 리뷰를 대체하지 않는다.
- `code-review`/`security-review`는 매 커밋마다 무조건 새로 돌릴 필요는 없다 — 작은 수정 반복 중엔 스킵하고, 기능 단위가 완결되는 시점(PR 직전)에 몰아서 적용해도 된다. 단, 토큰·인증·외부 노출을 건드렸다면 즉시 `security-review`.
- 확신이 안 서는 변경은 사람에게 근거와 함께 확인받을 것.

---

## 7. 커밋 컨벤션

- 형식: `<type>: <한글 설명>` — type은 `feat` / `fix` / `ci` / `test` / `docs` / `refactor`.
- 예: `feat: fetch_recent_logs tool 추가 — 전체 레벨 최신 로그 조회`
- 커밋은 **사용자가 명시적으로 요청할 때만** 수행. main 브랜치면 먼저 브랜치를 판다.

---

## 8. 하지 말 것 (금지사항)

- `application.yml` / `.env` 의 **실제 시크릿·DB 접속정보·토큰을 커밋하거나 로그에 노출**하지 말 것.
- CI에서 **테스트 스킵**(`build -x test`) 추가 금지.
- Docker 이미지 태그를 `latest` 단독으로 바꾸지 말 것 (기존처럼 `latest` + `${sha}` 병행).
- 배포 워크플로우의 **헬스체크/롤백 로직을 임의로 제거**하지 말 것.
- Actuator 포트(9090)를 외부 노출하는 설정 추가 금지 (보안 설계 핵심 — Forwarder만 내부망 접근).
- MCP tool 레이어에 비즈니스 로직·트랜잭션을 넣지 말 것.

---

## 9. 자주 쓰는 명령

```bash
./gradlew test          # 전체 테스트
./gradlew build         # 빌드(테스트 포함)
./gradlew bootRun       # 로컬 실행
gh run list --limit 5   # 최근 CI 결과
```
