import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

export const options = {
    vus: 50,           // 50명의 가상 유저
    duration: '30s',   // 30초 동안 테스트
};

const SERVER_NAME = 'test002';
const URL = `http://localhost:8080/api/servers/${SERVER_NAME}/ingest/logs`;
const MCP_TOKEN = '5c08ea68-0635-4168-90cd-b52052afd7bd';
const DISCORD_WEBHOOK_URL = '';

export default function () {
    const payload = [];
    const batchSize = 100;
    const now = Date.now();

    const currentVU = exec.vu.idInTest;
    const currentIter = exec.vu.iterationInInstance;

    for (let i = 0; i < batchSize; i++) {
        let eventIdStr;

        if (currentVU === 1) {
            eventIdStr = `deadlock-trap-seq${i}`;
        } else if (currentVU === 2) {
            eventIdStr = `deadlock-trap-seq${batchSize - 1 - i}`;
        }
        else {
            eventIdStr = `safe-zone-vu${currentVU}-iter${currentIter}-seq${i}`;
        }

        payload.push({
            eventId: eventIdStr,
            ts: now + i,
            level: 'INFO',
            message: currentVU <= 5 ? `[RETRY] realistic load test message ${i}` : `[NORMAL] realistic load test message ${i}`
        });
    }

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-MCP-TOKEN': MCP_TOKEN,
            'X-DISCORD-WEBHOOK-URL': DISCORD_WEBHOOK_URL
        },
        timeout: '1s'
    };

    const res = http.post(URL, JSON.stringify(payload), params);

    // 💡 블로그 캡처를 위해 보기 좋게 질문(Check) 수정
    check(res, {
        'status is 200 (Success)': (r) => r.status === 200,
        'status is 500 (Deadlock/Error)': (r) => r.status === 500,
        'is Timeout (5s 초과)': (r) => r.error === 'timeout',
    });

    sleep(0.1);
}