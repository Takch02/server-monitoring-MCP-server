# 장애 상세 웹

첫 오류 알림의 자세히 보기 링크로 대표 로그와 동일 서버·오류 그룹의 발생 기록을 확인한다. 첫 알림에 배정된 대표 로그의 수신 시각 5분 전부터 링크를 발급하는 시점까지 조회한다. 범위의 끝은 대표 로그 수신 5분 뒤까지만 늘어난다. 조회된 로그 ID를 저장한다. 이후 유입된 로그는 기존 사건에 추가하지 않는다. 대표 로그의 실제 발생 시각은 KST로, 조회 범위는 서버의 수신 시각으로 표시한다.

공유 링크의 유효기간은 발급 시점부터 24시간이다. 링크를 전달받은 사람도 해당 사건을 열 수 있다. 브라우저는 URL의 fragment에서 토큰을 읽어 API의 Authorization 헤더로만 전달한다. 서버는 사건 ID, 만료 시각과 서명을 검증한다. 대표 로그와 발생 기록은 응답 시 자격 증명 패턴을 마스킹하고 16,384자로 제한한다. 원문은 화면에서 텍스트로 표시한다.

## 개발

- Java 서버에 `INCIDENT_PUBLIC_URL=https://server-doctor.example`, `INCIDENT_LINK_KEY` 및 기존 서버 환경 변수를 설정해 실행한다. 링크 키는 `openssl rand -base64 32`로 생성한다.
- `web/`에서 `npm ci` 후 같은 `INCIDENT_LINK_KEY`를 개발 서버 환경에 설정하고 `npm run dev`를 실행한다. 대조용 링크의 origin을 Vite의 로컬 origin으로 바꿔 연다. Vite의 개발 프록시가 Spring(8080)에 연결한다.
- Node.js 22.12 이상을 사용한다. `npm run build` 결과물은 `web/dist/`에 생성된다.

## Oracle 배포

1. `web/dist/`의 내용을 중앙 VM에 복사한다. 현재 Docker Caddy에 [Caddyfile 예시](../deploy/incident-web.Caddyfile)를 적용하고, 해당 정적 파일 디렉터리를 `/srv/server-doctor-web`으로 마운트한다. 기존 인증서 `/data` 볼륨과 호스트 네트워크를 사용한다.
2. Caddy 컨테이너와 중앙 Spring 서버 모두에 같은 `INCIDENT_LINK_KEY`를 설정한다. 이 키는 기존 `ALERT_WEBHOOK_KEY`와 별도로 생성하며, Caddy에는 별도의 환경 파일로 전달한다. Caddy가 HTTPS 사건 API 요청에 내부 헤더를 설정한다. Spring에 직접 접근하면 해당 헤더가 없어서 사건 API는 404를 반환한다.
3. 중앙 Spring 서버에 `INCIDENT_PUBLIC_URL=https://server-doctor.xyz`를 설정한 뒤 배포한다. Flyway V5가 자동 적용된다. 공개 주소를 비워 두면 링크 발급이 비활성화된다. 발급 시 사용한 키가 바뀌면 이전 공유 링크는 만료 시각과 상관없이 열 수 없으므로 재배포 때 같은 키를 사용한다.
4. 새 데모 서버를 등록해 첫 오류를 한 건 발생시킨다. Discord 링크를 새 브라우저에서 열고, 페이지를 새로고침한 뒤 동일한 사건을 표시하는지 확인한다. 기존에 이미 첫 알림을 보낸 서버는 이번 단계의 첫 알림을 검증할 수 없으므로 새 서버 이름을 쓴다.

기존 Caddy를 Docker로 실행했다면 배포 시 별도 환경 파일과 빌드 디렉터리를 추가해 컨테이너를 재생성한다. 예시는 기존 `~/server-doctor-https` 경로와 볼륨 이름을 사용한다. 환경 파일에는 `INCIDENT_LINK_KEY` 한 줄을 넣고, 기존 컨테이너를 중지하기 전에 환경 파일과 `dist/index.html`을 준비한다.

```bash
cd ~/server-doctor-https
docker stop server-doctor-caddy &&
docker rename server-doctor-caddy server-doctor-caddy-before-incidents &&
docker run -d --name server-doctor-caddy \
  --restart unless-stopped --network host \
  --env-file "$PWD/incident-web.env" \
  -v "$PWD/incident-web.Caddyfile:/etc/caddy/Caddyfile:ro" \
  -v "$PWD/dist:/srv/server-doctor-web:ro" \
  -v server-doctor-caddy-data:/data \
  -v server-doctor-caddy-config:/config \
  caddy:2-alpine
```

Caddy 재생성 중에는 HTTPS 연결이 잠시 끊길 수 있다. 새 컨테이너가 정상인지 `curl -fsS https://server-doctor.xyz/actuator/health`로 확인한다.

기본 Caddy의 `/mcp`, 기존 수집 API, `/actuator/health`는 기존 Spring으로 전달된다. 5분 요약의 그룹별 링크, CPU/JVM 그래프, 랜딩과 대시보드는 다음 단계 범위다.
