# MCP Tool 추가/수정 절차 (주요 반복 작업)

새 진단 도구를 추가할 때:

1. `service`에 로직 구현 (`~ForMcp` 형태의 문자열 반환 메서드가 관례) + **단위 테스트 작성**.
2. `mcp/ServerDoctorMcpTools.java`에 `@McpTool` 메서드 추가 — service 호출 + 결과 포맷만.
3. `name`은 `<snake_case>` 규칙(접두사 없음 — PlayMCP가 등록 시 서버 식별 prefix를 자동으로 붙이므로 tool name에 MCP명을 넣지 않는다). `description`은 한국어 설명 뒤에 영문 요약을 병기하고 서비스명 `Server Doctor(서버 닥터)`를 포함한다. `@McpToolParam`도 LLM 관점에서 꼼꼼히. `annotations`(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint)는 필수이며 실제 동작에 맞게 지정한다.
4. 파라미터 기본값·널 처리는 tool 메서드에서 방어적으로 (기존 `sinceHours` 패턴 참고).
5. `./gradlew test` 통과 확인.

코드 스타일은 [conventions.md](./conventions.md), 테스트 작성 규칙은 [testing.md](./testing.md) 참고.
