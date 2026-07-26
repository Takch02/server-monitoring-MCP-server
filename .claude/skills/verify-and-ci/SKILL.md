---
name: verify-and-ci
description: Use when the user asks to "verify", "검증", "테스트 확인", "CI 점검", "배포 전 확인", "PR 전 확인", or wants to check if changes are safe to push/merge. Runs local tests and audits the CI/CD pipeline configuration for issues.
version: 1.1.0
---

# Verify & CI Check

PR 전 로컬 테스트 실행과 CI/CD 파이프라인 점검을 함께 수행하는 skill.

## 언제 사용하는가

- 브랜치를 push / PR 올리기 전
- 코드 변경 후 "동작하는지 확인해줘" 요청 시
- CI/CD 설정이 바뀌었을 때

---

## Step 1 — 로컬 테스트 실행

```bash
./gradlew test
```

- 실패한 테스트가 있으면 원인을 파악하고 수정 후 재실행
- 결과 요약: 통과 / 실패 수, 실패 케이스 목록

---

## Step 2 — CI/CD 파이프라인 점검

`.github/workflows/workflow.yml`을 읽고 아래 항목을 순서대로 확인한다.

### 체크리스트

| # | 항목 | 확인 방법 |
|---|------|-----------|
| 1 | **테스트 스킵 여부** | `gradlew build -x test` 패턴 탐지 → 위험 플래그 |
| 2 | **시크릿 하드코딩** | `password:`, `token:`, `key:` 값이 리터럴인지 확인 |
| 3 | **워크플로우 트리거** | `push: branches: [main]` 외 PR 트리거 누락 여부 |
| 4 | **Docker 이미지 태그** | `latest`만 쓰는지, SHA 태그 병행 여부 |
| 5 | **배포 전 헬스체크** | 새 컨테이너 기동 후 헬스체크 없이 구 컨테이너 바로 교체하는지 |
| 6 | **Docker push 조건** | PR 빌드 시 GHCR push가 실행되어 `latest` 태그를 덮어쓰는지 |

---

## Step 3 — 최근 GitHub Actions 결과 조회 (선택)

```bash
gh run list --limit 5
```

실패한 run이 있으면 `gh run view <run-id>` 로 상세 확인.

---

## 완료 기준

- [ ] `./gradlew test` 전체 통과
- [ ] CI/CD 체크리스트 항목 중 위험 항목 없음 (또는 인지 후 수용)
- [ ] 결과를 한 줄 요약으로 사용자에게 전달
