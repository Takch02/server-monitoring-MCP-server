# Forwarder 중앙 상태 보고

Forwarder가 앱을 관측한 결과(로그·지표·Health)와 Forwarder 자체의 수집·전송 상태를 구분한다.
`get_forwarder_status`는 최신 수집 상태를 제공하며 `diagnose_server`에도 같은 결과가 포함된다.
상태 보고가 없어도 기존 로그·지표·Health 조회는 가능하지만, 수집 신뢰성은 `UNKNOWN`이다.

## 수신과 저장

- `POST /api/servers/{serverName}/ingest/forwarder-status`
- 인증: 해당 서버의 `X-MCP-TOKEN`. 인증 실패는 401이며 상태는 갱신되지 않는다.
- 입력은 `ForwarderStatusIngestDto`의 버전 1 계약을 따른다. 예시는 `src/test/resources/fixtures/forwarder-status-v1.json`.
- 수집기 4개(`logs`, `log_sender`, `metrics`, `health`)를 모두 보고하며 비활성 수집기도 명시한다.
- 시각은 Unix 초(소수 허용), 카운터는 실행 이후 누적값이다. 큐 크기는 로그 **배치 수**다.
- 오류는 제한된 원인 코드와 예외 클래스명만 저장한다. 토큰·URL·로그 원문·HTTP 응답 본문은 저장하지 않는다.
- `forwarder_status`에 서버별 최신 행 하나를 갱신한다. 상태 변경 이력은 저장하지 않는다.
- 서버 행 잠금으로 동시 최초 생성과 교체를 직렬화한다. 같은 실행은 더 큰 순번만 저장한다.
- 다른 실행은 더 늦은 `started_at`만 저장한다. 오래된 실행의 지연 보고·중복 보고는 `200 {"accepted": false}`이며 수신 시각도 갱신하지 않는다.
- 실행 시작 순서는 Forwarder의 벽시계에 의존한다. 재시작 시 호스트 시간이 이전 실행보다 뒤로 이동했다면 보고가 무시될 수 있으므로 시간 동기화를 확인해야 한다. 한 서버 이름에는 활성 Forwarder 하나를 사용한다.

## 판정

- `UNKNOWN`: 보고 미수신, 마지막 서버 수신 이후 기본 75초 초과, 또는 유효하지 않은 서버 수신 시각.
- `DEGRADED`: 보고는 도착하지만 활성 루프 중단·스레드 실패·수집/전송 오류·최근 폐기·활성 수집기 없음 등이 관측됨.
- `OK`: 현재 활성 수집기의 동작 상태. 앱의 정상이나 과거 전체 데이터 수집을 보장하지 않는다.
- 오래된 폐기는 현재 상태가 복구되어도 누적 건수와 누락 가능성으로 표시한다. 새 실행은 카운터를 초기화한다.
- 중앙 보고 최신성은 **서버 수신 시각**으로 판정한다. 루프 활동·최근 폐기는 보고 안의 시각 차이로 검사한다.
- 루프 활동은 수집기가 보고한 `poll_stale_seconds`와 중앙 최신성 기준 중 큰 값을 사용한다. 긴 수집 주기의 정상 대기를 중단으로 오판하지 않으며, 이 필드가 없는 구형 보고는 중앙 기준을 사용한다.
- 기본 75초 기준은 `diagnostics.forwarder-stale-seconds`로 변경할 수 있다.
- 보고 중단만으로 앱·호스트·Forwarder·네트워크 중 어디에 문제가 있는지 단정하지 않는다.
- Health의 `DOWN`은 앱 응답 관측이다. 이를 정상적으로 수집·전송한 Forwarder는 `OK`일 수 있다.

## 연결과 배포

서버를 먼저 배포하고, 상태 보고 모듈을 포함한 Forwarder 이미지를 배포한다.
서버가 생성하는 설치 가이드에도 아래 주소가 포함된다.

```yaml
environment:
  SERVER_NAME: "${SERVER_NAME}"
  MCP_TOKEN: "${INGEST_TOKEN}"
  MCP_FORWARDER_STATUS_INGEST_URL: "${MCP_DOMAIN}/api/servers/${SERVER_NAME}/ingest/forwarder-status"
  FORWARDER_REPORT_INTERVAL: "15"
  FORWARDER_VERSION: "v1.0.0" # 실제 이미지 태그 또는 커밋으로 변경
```

상태 보고는 별도 스레드에서 직접 전송한다. 실패한 보고를 큐에 쌓거나 재시도하지 않고 다음 주기에 최신 상태를 보낸다.
로그 큐가 가득 차도 상태 보고는 별개로 진행된다. 상태 API가 실패해도 데이터 전송과 로컬 컨테이너 검사는 계속된다.
주소를 설정하지 않은 기존 Forwarder는 상태 보고를 비활성화하고 기존 수집을 계속한다.
실패 원인은 Forwarder 표준 출력의 `[status-report]`에서 확인한다. 중앙에 도달하지 못한 이유를 중앙 서버가 직접 알 수는 없다.

현재 프로젝트는 `spring.jpa.hibernate.ddl-auto=update`를 사용하므로 시작 시 새 테이블을 생성한다.
운영 DDL을 별도로 적용하는 환경에서는 서버 배포 전에 다음 테이블을 생성할 수 있다.

`target_server`의 `heartBeat`, `lastHealthStatus`, `lastHealthHttpStatus`, `lastHealthLatencyMs`는
엔티티 매핑과 갱신 로직에서 제거했다. 기존 DB 컬럼은 유지하며 이 작업에서 `DROP COLUMN`은 실행하지 않는다.
구버전 롤백과 외부 조회 여부를 확인한 뒤 별도 배포에서 삭제할 수 있다.
앱 Health는 `server_health_event`, 지표는 `server_metric`, Forwarder의 최근 보고는
`forwarder_status.received_at`을 사용한다. Health·Metrics 저장은 `target_server`를 갱신하지 않는다.
상태 보고의 동시 생성·교체를 위한 서버 행 잠금은 유지된다.

```sql
CREATE TABLE IF NOT EXISTS forwarder_status (
    server_id BIGINT NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    started_at DOUBLE NOT NULL,
    report_sequence BIGINT NOT NULL,
    received_at DATETIME(6) NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6)
);
```

## 검증 범위

Java 단위 테스트와 MockMvc + H2 통합 테스트로 인증·입력 검증·한 행 교체·동시 최초 보고·MCP 조회를 검증한다.
실제 Spring 설정에서 Health·Metrics 저장이 정상 동작하면서 대상 서버 엔티티 UPDATE가 발생하지 않는지 Hibernate 통계로 검증한다.
Forwarder는 임시 파일·localhost HTTP 서버·별도 Python 프로세스로 수집 실패·복구·보고 실패 격리·재시작을 검증한다.
운영 API·MySQL에는 테스트 요청이나 DDL을 실행하지 않는다.
