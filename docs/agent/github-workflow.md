# GitHub PR / Issue 생성 규칙

## 제목 형식

- PR과 Issue 제목은 반드시 `[TYPE] 한글 요약` 형식을 사용한다.
- `TYPE`은 `FEAT` / `FIX` / `REFACTOR` / `CI` / `TEST` / `DOCS` / `CHORE` 중 하나를 대문자로 쓴다.
- 예: `[FEAT] MCP 도구 호출 제한 추가`
- 커밋 메시지는 [commit.md](commit.md)의 `<type>: 한글 설명` 형식을 그대로 사용한다.

## PR 생성

1. 변경 범위와 base 브랜치를 확인하고, 관련 없는 변경은 포함하지 않는다.
2. [verification.md](verification.md)의 PR 전 검증을 완료한다.
3. 제목은 위 형식으로 작성하고, 본문에는 아래 항목을 포함한다.
   - `## 변경 사항`: 무엇을 바꿨는가
   - `## 변경 이유`: 왜 필요한가
   - `## 검증`: 실행한 테스트·점검
   - 호환성·배포 영향이 있으면 그 내용
4. 사용자가 ready-for-review를 요청하지 않으면 draft PR로 생성한다.

```bash
gh pr create --draft --base main --head <branch> \
  --title "[FEAT] 한글 요약" --body-file <pr-body-file>
```

## Issue 생성

- Issue는 사용자가 명시적으로 요청할 때만 생성한다.
- 제목은 PR과 같은 `[TYPE] 한글 요약` 형식을 사용한다.
- 본문에는 `## 배경`, `## 작업 범위`, `## 완료 조건`을 포함한다.

```bash
gh issue create --title "[FIX] 한글 요약" --body-file <issue-body-file>
```
