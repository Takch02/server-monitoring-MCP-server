# 장애 상세 웹

오류 알림마다 자세히 보기 링크를 제공한다. 상세는 발송 시점에 배정된 로그와 발송 성공 후 5분 동안 수신한 같은 서버의 모든 유형의 오류를 보여준다. 10:00에 발송한 링크를 10:04에 열면 그때까지 들어온 오류가 보이고, 11:00에 열면 10:05 직전까지 수신된 오류가 보인다. 10:05 이후에 새로 들어온 오류가 있으면 다음 알림을 보낸다. 각 로그의 실제 발생 시각은 KST로 표시한다.

공유 링크의 유효기간은 발급 시점부터 24시간이다. 링크를 전달받은 사람도 해당 사건을 열 수 있다. 브라우저는 URL의 fragment에서 토큰을 읽어 API의 Authorization 헤더로만 전달한다. 서버는 사건 ID, 만료 시각과 서명을 검증한다. 대표 로그와 발생 기록은 응답 시 자격 증명 패턴을 마스킹하고 16,384자로 제한한다. 원문은 화면에서 텍스트로 표시한다.

## 개발

- Java 서버에 `INCIDENT_PUBLIC_URL=https://server-doctor.example`, `INCIDENT_LINK_KEY` 및 기존 서버 환경 변수를 설정해 실행한다. 링크 키는 `openssl rand -base64 32`로 생성한다.
- `web/`에서 `npm ci` 후 같은 `INCIDENT_LINK_KEY`를 개발 서버 환경에 설정하고 `npm run dev`를 실행한다. 대조용 링크의 origin을 Vite의 로컬 origin으로 바꿔 연다. Vite의 개발 프록시가 Spring(8080)에 연결한다.
- Node.js 22.12 이상을 사용한다. `npm run build` 결과물은 `web/dist/`에 생성된다.

## Oracle 배포

`main`에 머지하면 Actions가 같은 커밋에서 Spring 이미지와 Caddy 웹 이미지를 만든다. 웹 이미지에 Vite 빌드 결과와 Caddyfile이 포함된다. Spring이 준비되면 Actions가 Oracle에서 웹 이미지를 가져와 Caddy를 교체한다.

중앙 Spring 서버의 `~/mcp-server/.env`에 두 값을 한 번 설정한다.

- `INCIDENT_PUBLIC_URL=https://server-doctor.xyz`
- `INCIDENT_LINK_KEY`에는 `openssl rand -base64 32`로 만든 키를 넣는다. 이미 설정한 키가 있으면 재사용한다. 키를 바꾸면 이전 공유 링크도 만료된다.

Actions는 실행 중인 Spring 컨테이너에서 이 키를 읽어 Caddy에 전달한다. 기존 Caddy의 인증서와 설정 볼륨을 재사용하고, 상세 HTML·JavaScript와 Spring 헬스를 HTTPS로 확인한다. 이 확인이 실패하면 이전 Caddy를 다시 시작한다.

`/incidents/{id}`는 Caddy가 React 화면을 제공하는 주소이고, `/api/incidents/{id}`는 화면이 Spring에 데이터를 요청하는 주소다. 배포 결과는 아래 명령과 데모 서버 알림으로 확인한다.

```bash
curl -fsS https://server-doctor.xyz/actuator/health
curl -fsS https://server-doctor.xyz/incidents/deploy-check | grep -F '<div id="root"></div>'
```

데모 서버에서 알림을 받은 뒤 5분 안에 다른 유형의 오류를 추가로 발생시킨다. 같은 링크를 새로고침해 추가 오류가 표시되는지, 5분이 지나도 해당 오류로 새 메시지가 오지 않는지 확인한다. 5분이 지난 뒤 새 오류를 발생시켜 별도 알림과 링크가 도착하는지 확인한다.
