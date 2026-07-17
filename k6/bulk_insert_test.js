import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

// ── 설정 ────────────────────────────────────────────────────────────────────
const BASE_URL   = __ENV.BASE_URL   || "http://localhost:8080";
const BATCH_SIZE = parseInt("100");  // 요청당 로그 수
const SERVER_NAME = 'test_server';
const MCP_TOKEN  = 'af179b80-b2e1-4c60-bead-e1fad115c5b2';
// ── 부하 시나리오 ─────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    bulk_insert: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "10s", target: 5  },  // 워밍업
        { duration: "30s", target: 20 },  // 부하 증가
        { duration: "20s", target: 20 },  // 유지
        { duration: "10s", target: 0  },  // 종료
      ],
    },
  },
  thresholds: {
    http_req_failed:   ["rate<0.01"],   // 에러율 1% 미만
    http_req_duration: ["p(95)<2000"],  // 95th percentile 2초 미만
  },
};

// ── 커스텀 메트릭 ─────────────────────────────────────────────────────────────
const logsInserted = new Counter("logs_inserted_total");
const insertDuration = new Trend("batch_insert_duration");

// ── 헬퍼 ─────────────────────────────────────────────────────────────────────
const LEVELS   = ["INFO", "INFO", "INFO", "WARN", "ERROR"]; // 60/20/20 비율
const MESSAGES = [
  "Request received: GET /api/orders",
  "User authenticated successfully: userId={}",
  "Cache hit for key: product:{}",
  "Order processed successfully: orderId={}",
  "Slow query detected ({}ms): SELECT * FROM orders",
  "Retry attempt {}/3 for external API call",
  "Unhandled exception\njava.lang.NullPointerException: Cannot invoke method get()\n\tat com.example.service.OrderService.process(OrderService.java:42)",
  "Memory usage above 80%: {}MB used",
  "Batch job completed: processed {} records",
  "SQLTimeoutException: Timeout waiting for connection from pool",
];

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function makeBatch(size) {
  const now = Date.now();
  const batch = [];

  for (let i = 0; i < size; i++) {
    const ts    = now - randomInt(0, 5000);  // 최근 5초 내 랜덤 시각
    const level = LEVELS[randomInt(0, LEVELS.length - 1)];
    const msg   = MESSAGES[randomInt(0, MESSAGES.length - 1)]
                    .replace("{}", String(randomInt(1, 9999)));

    // eventId: 중복 방지를 위해 vu + iter + index 조합
    const eventId = `${__VU}-${__ITER}-${i}-${ts}`;

    batch.push({ eventId, ts, level, message: msg });
  }

  return batch;
}

// ── 메인 ─────────────────────────────────────────────────────────────────────
export default function () {
  const url     = `${BASE_URL}/test/ingest/logs/batch/1`;
  const payload = JSON.stringify(makeBatch(BATCH_SIZE));
  const headers = { "Content-Type": "application/json" };

  const start = Date.now();
  const res   = http.post(url, payload, { headers });
  insertDuration.add(Date.now() - start);

  const ok = check(res, {
    "status 200": (r) => r.status === 200,
  });

  if (ok) {
    logsInserted.add(BATCH_SIZE);
  } else {
    console.error(`[VU ${__VU}] 실패: ${res.status} - ${res.body}`);
  }

  sleep(0.1);
}

// ── 종료 요약 ─────────────────────────────────────────────────────────────────
export function handleSummary(data) {
  const inserted = data.metrics.logs_inserted_total?.values?.count ?? 0;
  const rps      = data.metrics.http_reqs?.values?.rate?.toFixed(2) ?? "-";
  const p95      = data.metrics.http_req_duration?.values["p(95)"]?.toFixed(0) ?? "-";
  const errRate  = ((data.metrics.http_req_failed?.values?.rate ?? 0) * 100).toFixed(2);

  console.log("═══════════════════════════════════");
  console.log(`총 삽입 로그 수 : ${inserted}`);
  console.log(`요청 처리량     : ${rps} req/s`);
  console.log(`p95 응답시간    : ${p95} ms`);
  console.log(`에러율          : ${errRate}%`);
  console.log("═══════════════════════════════════");

  return {};
}
