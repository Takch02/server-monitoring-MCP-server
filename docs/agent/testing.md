# 테스트 규칙 (필수)

- **새 service 로직에는 단위 테스트가 필수다.** 버그 수정 시에는 회귀 방지 테스트를 우선 추가.
- 스타일: JUnit5 + `@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`,
  **AssertJ**(`assertThat`) + **BDDMockito**(`given`/`then`).
- 테스트 메서드명은 기존처럼 **한글**로 상황을 서술 (예: `잘못된토큰_SecurityException`).
- given/when/then 구조를 유지.
