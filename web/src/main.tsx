import { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

type Occurrence = {
  id: string;
  occurredAt: string | null;
  message: string;
  truncated: boolean;
};

type Incident = {
  id: string;
  serverName: string;
  errorGroupKey: string;
  windowStart: string;
  windowEnd: string;
  expiresAt: string;
  occurrenceCount: number;
  representative: Occurrence | null;
  occurrences: Occurrence[];
  nextCursor: string | null;
};

const pathMatch = /^\/incidents\/([0-9a-f-]{36})\/?$/.exec(window.location.pathname);
const incidentId = pathMatch?.[1];
const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
const timeFormat = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Asia/Seoul',
});
const numberFormat = new Intl.NumberFormat('ko-KR');

function formatTime(time: string | null) {
  return time ? timeFormat.format(new Date(time)) : '발생 시각 없음';
}

function formatWindowTime(time: string) {
  return time.replace('T', ' ').slice(0, 19);
}

class InvalidLink extends Error {}

async function loadIncident(before: string | null, signal?: AbortSignal): Promise<Incident> {
  const query = before ? '?' + new URLSearchParams({ before }) : '';
  const response = await fetch(`/api/incidents/${incidentId}${query}`, {
    headers: { Authorization: `Bearer ${token}` },
    credentials: 'omit',
    cache: 'no-store',
    signal,
  });
  if (response.status === 404) throw new InvalidLink();
  if (!response.ok) throw new Error('Request failed');
  return response.json() as Promise<Incident>;
}

function Log({ occurrence }: { occurrence: Occurrence }) {
  return (
    <>
      <pre className="log"><code>{occurrence.message || '저장된 로그 메시지가 없습니다.'}</code></pre>
      {occurrence.truncated && <p className="log-note">로그가 길어 앞부분만 표시합니다.</p>}
    </>
  );
}

function IncidentScreen() {
  const [incident, setIncident] = useState<Incident | null>(null);
  const [failure, setFailure] = useState<'link' | 'request' | null>(
    token && incidentId ? null : 'link',
  );
  const [attempt, setAttempt] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);

  useEffect(() => {
    if (!token || !incidentId) return;
    const controller = new AbortController();
    loadIncident(null, controller.signal).then(setIncident).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setFailure(error instanceof InvalidLink ? 'link' : 'request');
    });
    return () => controller.abort();
  }, [attempt]);

  async function showMore() {
    if (incident?.nextCursor == null || loadingMore) return;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const next = await loadIncident(incident.nextCursor);
      setIncident({ ...next, occurrences: [...incident.occurrences, ...next.occurrences] });
    } catch (error) {
      if (error instanceof InvalidLink) {
        setIncident(null);
        setFailure('link');
      } else {
        setMoreFailed(true);
      }
    } finally {
      setLoadingMore(false);
    }
  }

  if (failure || !incident) {
    return (
      <main className="status-panel" aria-live="polite">
        <p className="section-label">장애 상세</p>
        <h1>{failure === 'link' ? '이 링크를 열 수 없습니다' :
          failure === 'request' ? '데이터를 불러오지 못했습니다' : '장애 정보를 불러오는 중입니다'}</h1>
        {failure === 'link' && <p>Discord에서 받은 링크 전체를 확인하세요. 공유 링크는 발급 후 24시간 동안 유효합니다.</p>}
        {failure === 'request' && <button onClick={() => { setFailure(null); setAttempt(attempt + 1); }}>다시 시도</button>}
      </main>
    );
  }

  const exceptionType = incident.representative?.message.match(/\b[\w$]+(?:Exception|Error)\b/)?.[0];

  return (
    <main className="incident">
      <header className="incident-header">
        <p className="section-label"><span className="signal" /> 서버 오류</p>
        <h1>{exceptionType ?? '장애 상세'}</h1>
        <p className="headline-meta">{incident.serverName} · {formatTime(incident.representative?.occurredAt ?? null)} · KST</p>
      </header>

      <section className="facts" aria-label="사건 정보">
        <div><span>오류 그룹</span><strong className="mono" title={incident.errorGroupKey}>{incident.errorGroupKey.slice(0, 8)}</strong></div>
        <div><span>발생 건수</span><strong>{numberFormat.format(incident.occurrenceCount)}건</strong></div>
        <div><span>링크 만료</span><strong>{formatTime(incident.expiresAt)} KST</strong></div>
      </section>

      <div className="columns">
        <section className="panel">
          <h2>대표 로그</h2>
          {incident.representative ? <Log occurrence={incident.representative} /> : <p>대표 로그를 더 이상 조회할 수 없습니다.</p>}
        </section>

        <aside className="panel history">
          <h2>발생 기록</h2>
          <p className="window"><span>수집 시간 범위 · 서버 기준</span>
            <time>{formatWindowTime(incident.windowStart)}</time><time>{formatWindowTime(incident.windowEnd)}</time>
          </p>
          {incident.occurrences.length === 0 && <p>조회 가능한 기록이 없습니다.</p>}
          <div className="occurrences">
            {incident.occurrences.map((entry) => (
              <details key={entry.id}>
                <summary><span>{formatTime(entry.occurredAt)}{entry.occurredAt && ' KST'}</span>
                  {entry.id === incident.representative?.id && <small>대표</small>}
                </summary>
                <Log occurrence={entry} />
              </details>
            ))}
          </div>
          {incident.nextCursor !== null &&
            <button className="more" disabled={loadingMore} onClick={showMore}>{loadingMore ? '불러오는 중' : '발생 기록 더 보기'}</button>}
          {moreFailed && <p role="alert">기록을 더 불러오지 못했습니다. 다시 시도해 주세요.</p>}
        </aside>
      </div>
    </main>
  );
}

function App() {
  return (
    <>
      <div className="masthead"><div><span className="brand-mark" aria-hidden="true" /> Server Doctor</div></div>
      <IncidentScreen />
    </>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
