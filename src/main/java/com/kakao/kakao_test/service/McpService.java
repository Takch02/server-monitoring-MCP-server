package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.*;
import com.kakao.kakao_test.exception.NotFoundException;
import com.kakao.kakao_test.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@Slf4j
@RequiredArgsConstructor
public class McpService {

    // TODO: 타임아웃 설정 권장(데모는 기본 RestClient)
    private final RestClient restClient = RestClient.create();

    public final Map<String, TargetServer> serverStore = new ConcurrentHashMap<>();

    // 서버별 최근 로그 링버퍼
    private final Map<String, Deque<LogEventDto>> logBuffers = new ConcurrentHashMap<>();
    private static final int MAX_LOGS_PER_SERVER = 10_000;

    /**
     * 서버 이름 가져오기
     */
    public TargetServer getServerOrThrow(String name) {
        TargetServer server = serverStore.get(name);
        if (server == null) throw new NotFoundException("서버가 존재하지 않습니다: " + name);
        return server;
    }

    /**
     * 서버 등록
     * 사용자 서버와 통신하려면 private key가 있어야 하므로 UUID 를 생성하여 사용자 서버에게 전달함.
     */
    public RegisterServerResponse registerServer(RegisterServerRequest req) {
        if (req.getServerName() == null || req.getServerName().isBlank()) {
            throw new IllegalArgumentException("serverName은 필수입니다.");
        }
        if (req.getUrl() == null || req.getUrl().isBlank()) {
            throw new IllegalArgumentException("url은 필수입니다.");
        }

        String token = UUID.randomUUID().toString(); // 데모용
        TargetServer server = new TargetServer(req.getServerName(), req.getUrl(), req.getHealthPath(), token);
        serverStore.put(server.getName(), server);

        logBuffers.putIfAbsent(server.getName(), new ConcurrentLinkedDeque<>());

        log.info("✅ 서버 등록 완료: {}", server.getName());

        return new RegisterServerResponse(
                server.getName(),
                server.getUrl(),
                server.getHealthPath(),
                server.getIngestToken()
        );
    }

    /**
     * 사용자 서버 URL 변경
     */
    public void updateServerUrl(String name, String newUrl) {
        TargetServer server = getServerOrThrow(name);
        server.updateUrl(newUrl);
        log.info("🔁 서버 URL 갱신: {} -> {}", name, server.getUrl());
    }

    /**
     * 사용자 서버 헬스체크
     */
    public HealthCheckDto checkHealth(String name) {
        TargetServer server = getServerOrThrow(name);
        String fullUrl = server.getUrl() + server.getHealthPath();

        try {
            String result = restClient.get()
                    .uri(fullUrl)
                    .retrieve()
                    .body(String.class);

            return new HealthCheckDto(server.getName(), fullUrl, true, result);

        } catch (Exception e) {
            return new HealthCheckDto(server.getName(), fullUrl, false, "연결 실패: " + e.getMessage());
        }
    }


    // 로그 수신(PUSH): 서버/포워더가 MCP로 전송
    // 로그를 events 로 가져오면 logBuffer에 저장
    public IngestResultDto ingestLogs(String serverName, String token, List<LogEventDto> events) {
        TargetServer server = getServerOrThrow(serverName);
        verifyToken(server, token);

        if (events == null || events.isEmpty()) {
            return new IngestResultDto(serverName, 0, "수신할 로그가 없습니다.");
        }

        Deque<LogEventDto> q = logBuffers.computeIfAbsent(serverName, k -> new ConcurrentLinkedDeque<>());

        int accepted = 0;
        for (LogEventDto e : events) {
            if (e == null) continue;
            q.addLast(e);
            accepted++;

            // 링버퍼 크기 제한
            while (q.size() > MAX_LOGS_PER_SERVER) q.pollFirst();
        }

        return new IngestResultDto(serverName, accepted, "로그 수신 완료");
    }

    /**
     * 로그 분석 메소드
     */
    public ErrorLogAnalysisDto analyzeErrorLogs(String name, int limit) {
        getServerOrThrow(name);
        Deque<LogEventDto> q = logBuffers.get(name);

        if (q == null || q.isEmpty()) {
            return new ErrorLogAnalysisDto(name, List.of(), 0, "✅ 수집된 로그가 없습니다.");
        }

        // 1. 최근 로그 가져오기
        int size = q.size();
        int skip = Math.max(0, size - Math.max(1, limit));
        List<LogEventDto> recent = q.stream().skip(skip).toList();

        // 2. 에러 필터링 + "요약 및 중복 처리"
        List<String> errors = new ArrayList<>();
        String lastMsg = "";
        int duplicateCount = 0;

        for (LogEventDto e : recent) {
            // 에러가 아니면 패스
            if (e.getLevel() == null || (!"ERROR".equalsIgnoreCase(e.getLevel()) && !containsExceptionHint(e.getMessage()))) {
                continue;
            }

            String currentMsg = safe(e.getMessage());

            // (A) 중복 제거 로직: 방금 본 에러랑 똑같으면 카운트만 올리고 저장 안 함
            // (너무 긴 메시지는 앞부분 100자만 비교하는 식으로 최적화 가능)
            if (currentMsg.equals(lastMsg)) {
                duplicateCount++;
                continue;
            }

            // 이전 중복 에러가 있었다면 기록
            if (duplicateCount > 0) {
                errors.add("   ㄴ (위와 동일한 에러가 " + duplicateCount + "번 더 반복되었습니다.)");
                duplicateCount = 0;
            }

            // (B) 길이 제한 로직: LLM이 읽기 편하게 500자까지만 자름
            String displayMsg = currentMsg;
            if (displayMsg.length() > 500) {
                displayMsg = displayMsg.substring(0, 500) + "\n   ... (내용이 너무 길어 생략됨) ...";
            }

            errors.add(e.getTs() + " " + safe(e.getLevel()) + " " + displayMsg);
            lastMsg = currentMsg;
        }

        // 마지막에 남은 중복 카운트 처리
        if (duplicateCount > 0) {
            errors.add("   ㄴ (위와 동일한 에러가 " + duplicateCount + "번 더 반복되었습니다.)");
        }

        if (errors.isEmpty()) {
            return new ErrorLogAnalysisDto(name, List.of(), 0, "✅ 최근 구간에서 에러가 없습니다.");
        }

        return new ErrorLogAnalysisDto(
                name,
                errors, // 요약된 리스트 반환
                errors.size(),
                "⚠️ 최근 에러 로그가 발견되었습니다."
        );
    }

    private boolean containsExceptionHint(String msg) {
        if (msg == null) return false;
        return msg.contains("Exception") || msg.contains("ERROR") || msg.contains("Caused by");
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    // [3] 재시작(데모): 실제 운영에서는 SSM/k8s/런북 엔진으로 대체 권장
    public RestartResultDto restartServer(String name, RestartRequest req) {
        TargetServer server = getServerOrThrow(name);

        boolean dryRun = req != null && req.isDryRun();
        String confirm = (req == null) ? null : req.getConfirmToken();

        if (dryRun) {
            return new RestartResultDto(server.getName(), true, "DRY-RUN: 재시작 시뮬레이션(실제 실행 없음)");
        }

        // 데모용 2단계 확인 토큰
        if (!"CONFIRM".equals(confirm)) {
            return new RestartResultDto(server.getName(), false, "실행 거부: confirmToken=CONFIRM 값을 보내야 실행됩니다.");
        }

        log.warn("🚨 사용자의 요청으로 서버({}) 재시작 명령(데모)을 수행합니다...", server.getName());

        try {
            Thread.sleep(1200);
            return new RestartResultDto(server.getName(), true, "재시작 명령이 성공적으로 전송되었습니다(데모).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RestartResultDto(server.getName(), false, "재시작 중 오류: " + e.getMessage());
        }
    }

    private void verifyToken(TargetServer server, String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("X-MCP-TOKEN 헤더가 필요합니다.");
        }
        if (!server.getIngestToken().equals(token)) {
            throw new UnauthorizedException("토큰이 유효하지 않습니다.");
        }
    }
}
