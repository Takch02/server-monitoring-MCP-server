# 장애 상세 웹

첫 오류 알림의 자세히 보기 링크로 대표 로그와 동일 서버·오류 그룹의 발생 기록을 확인한다. 첫 오류 수신 시각을 시작으로 5분 구간을 고정한다. 상세 화면을 열 때마다 해당 구간에서 현재까지 수집된 로그를 조회한다. 예를 들어 10:00에 시작한 사건을 10:04에 열면 지금까지 들어온 오류를, 11:00에 열면 10:00 이상 10:05 미만에 수신한 오류를 보여준다. 목록은 수신 시각순으로 정렬하며 각 로그의 실제 발생 시각을 KST로 표시한다.

공유 링크의 유효기간은 발급 시점부터 24시간이다. 링크를 전달받은 사람도 해당 사건을 열 수 있다. 브라우저는 URL의 fragment에서 토큰을 읽어 API의 Authorization 헤더로만 전달한다. 서버는 사건 ID, 만료 시각과 서명을 검증한다. 대표 로그와 발생 기록은 응답 시 자격 증명 패턴을 마스킹하고 16,384자로 제한한다. 원문은 화면에서 텍스트로 표시한다.

## 개발

- Java 서버에 `INCIDENT_PUBLIC_URL=https://server-doctor.example`, `INCIDENT_LINK_KEY` 및 기존 서버 환경 변수를 설정해 실행한다. 링크 키는 `openssl rand -base64 32`로 생성한다.
- `web/`에서 `npm ci` 후 같은 `INCIDENT_LINK_KEY`를 개발 서버 환경에 설정하고 `npm run dev`를 실행한다. 대조용 링크의 origin을 Vite의 로컬 origin으로 바꿔 연다. Vite의 개발 프록시가 Spring(8080)에 연결한다.
- Node.js 22.12 이상을 사용한다. `npm run build` 결과물은 `web/dist/`에 생성된다.

## Oracle 배포

1. 중앙 VM에 배포마다 새 디렉터리(예: `~/server-doctor-https/releases/incident-v1`)를 만든다. 여기에 [Caddyfile 예시](../deploy/incident-web.Caddyfile), 빌드 결과 `dist/`, `INCIDENT_LINK_KEY`가 담긴 `incident-web.env`를 준비한다. 이전 컨테이너가 사용 중인 디렉터리는 그대로 둔다. 기존 인증서 `/data` 볼륨과 호스트 네트워크는 재사용한다.
2. Caddy 컨테이너와 중앙 Spring 서버 모두에 같은 `INCIDENT_LINK_KEY`를 설정한다. 이 키는 기존 `ALERT_WEBHOOK_KEY`와 별도로 생성하며, Caddy에는 별도의 환경 파일로 전달한다. Caddy가 HTTPS 사건 API 요청에 내부 헤더를 설정한다. Spring에 직접 접근하면 해당 헤더가 없어서 사건 API는 404를 반환한다.
3. 중앙 Spring 서버에 `INCIDENT_PUBLIC_URL=https://server-doctor.xyz`를 설정한 뒤 배포한다. Flyway V5가 자동 적용된다. 공개 주소를 비워 두면 링크 발급이 비활성화된다. 발급 시 사용한 키가 바뀌면 이전 공유 링크는 만료 시각과 상관없이 열 수 없으므로 재배포 때 같은 키를 사용한다.
4. 새 데모 서버를 등록해 첫 오류를 한 건 발생시킨다. Discord 링크를 새 브라우저에서 열고, 페이지를 새로고침한 뒤 초기 5분 안의 새 오류가 표시되고, 이후 수신된 오류는 표시되지 않는지 확인한다. 기존에 이미 첫 알림을 보낸 서버는 이번 단계의 첫 알림을 검증할 수 없으므로 새 서버 이름을 쓴다.

아래 `release`에 새 배포 디렉터리를 지정한다. 새 컨테이너를 먼저 생성하고, 기존 Caddy는 고유한 백업 이름으로 바꾼 뒤 잠시 중지한다. 새 Caddy가 HTTPS로 헬스체크와 상세 화면 요청에 정상 응답하면 운영 이름을 넘기고 기존 컨테이너를 삭제한다. 확인에 실패하면 기존 설정과 화면을 사용하던 컨테이너를 다시 실행한다.

```bash
(
  release="$HOME/server-doctor-https/releases/incident-v1"
  name=server-doctor-caddy
  test -f "$release/incident-web.env" && test -f "$release/incident-web.Caddyfile" &&
    test -f "$release/dist/index.html" || exit 1

  old_id=$(docker container inspect --format '{{.Id}}' "$name") || exit 1
  backup="$name-before-incidents-${old_id:0:12}"
  candidate="$name-next-${old_id:0:12}"
  new_id=$(docker create --name "$candidate" \
    --restart unless-stopped --network host \
    --env-file "$release/incident-web.env" \
    -v "$release/incident-web.Caddyfile:/etc/caddy/Caddyfile:ro" \
    -v "$release/dist:/srv/server-doctor-web:ro" \
    -v server-doctor-caddy-data:/data \
    -v server-doctor-caddy-config:/config \
    caddy:2-alpine) || exit 1
  if ! docker rename "$old_id" "$backup"; then
    docker rm "$new_id"
    exit 1
  fi

  if docker stop "$old_id" && docker start "$new_id"; then
    for attempt in {1..12}; do
      if health=$(curl --resolve server-doctor.xyz:443:127.0.0.1 -fsS --max-time 5 \
           https://server-doctor.xyz/actuator/health) &&
         grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$health" &&
         page=$(curl --resolve server-doctor.xyz:443:127.0.0.1 -fsS --max-time 5 \
           https://server-doctor.xyz/incidents/deploy-check) &&
         grep -Fq '<div id="root"></div>' <<< "$page" &&
         docker rename "$new_id" "$name"; then
        docker rm "$old_id"
        exit $?
      fi
      sleep 2
    done
  fi

  docker rm -f "$new_id" || exit 1
  docker rename "$old_id" "$name" && docker start "$old_id"
  exit 1
)
```

Caddy 재생성 중에는 HTTPS 연결이 잠시 끊길 수 있다. 기존 컨테이너를 복구했을 때도 명령은 실패 상태를 반환한다. 실제 공유 링크 검증은 위의 새 데모 서버로 진행한다.

기본 Caddy의 `/mcp`, 기존 수집 API, `/actuator/health`는 기존 Spring으로 전달된다. 5분 요약의 그룹별 링크, CPU/JVM 그래프, 랜딩과 대시보드는 다음 단계 범위다.
