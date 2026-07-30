# 코드 컨벤션

기존 코드를 따를 것.

- **Service**: `@Slf4j @Service @RequiredArgsConstructor` + `private final` 생성자 주입. 필드 주입(`@Autowired`) 금지.
- **DTO**: `record`. (예: `HealthIngestDto`, `LogEventDto`)
- **로깅**: `log.info/warn/error`. 한글 메시지 OK. 민감정보(토큰/비밀번호) 로깅 금지.
- **주석**: 단계가 있는 로직은 기존처럼 `// 1) ... // 2) ...` 번호 주석 스타일.
- **상수**: 매직넘버는 `private static final`로 (예: `STALE_SECONDS = 60`).
- **예외**: 도메인 예외는 `exception` 패키지 커스텀 예외를 쓰고 `GlobalExceptionHandler`에서 처리.
- **MCP Tool 설명**: `@McpTool(description=...)`·`@McpToolParam(description=...)`은 **LLM이 읽는 프롬프트**다.
  한글로 명확히, 기본값·예시(예: `168 (7일)`)까지 적을 것. 여기가 곧 사용성.
