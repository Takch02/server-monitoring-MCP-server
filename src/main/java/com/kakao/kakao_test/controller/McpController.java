package com.kakao.kakao_test.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import com.kakao.kakao_test.dto.RegisterServerRequest;
import com.kakao.kakao_test.service.LogService;
import com.kakao.kakao_test.service.ServerDoctorService;
import com.kakao.kakao_test.service.ServerRegisterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private final ServerDoctorService serverDoctorService;
    private final LogService logService;
    private final ServerRegisterService serverRegisterService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // SSE 연결 관리 (Thread-Safe)
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    @Value("${mcp.server-url}")
    private String serverUrl;
    // ========================================================================
    // 1. SSE 연결 엔드포인트 (PlayMCP가 접속하는 문)
    // ========================================================================
    @RequestMapping(
            value = "/sse",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter connect(@RequestBody(required = false) String body) {
        log.info("📢 MCP Connect Request");
        emitters.clear(); // 1. 기존 연결 정리

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        String id = String.valueOf(System.currentTimeMillis());
        emitters.put(id, emitter);

        log.info("🔌 PlayMCP 연결됨 (Session ID: {})", id);

        // SSE 수명 주기 관리
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));
        emitter.onError((e) -> emitters.remove(id));

        // 2. 비동기 스레드에서 이벤트 및 초기화 메시지 처리
        new Thread(() -> {
            try {
                Thread.sleep(500);

                // A. Endpoint 이벤트 전송 (필수)
                String finalUrl = serverUrl + "/mcp/messages?id=" + id;
                log.info("보내는 url : {}", finalUrl);
                emitter.send(SseEmitter.event().name("endpoint").data(finalUrl));
                log.info("✅ Endpoint 이벤트 전송 완료");

                // 요청 Body에 'initialize' 메시지가 있었다면 즉시 처리
                if (body != null && !body.isEmpty() && !body.equals("{}")) {
                    log.info("📩 연결 요청에 포함된 메시지 처리 중...");
                    handleMessage(body); // 기존 handleMessage 메서드 재사용
                }

            } catch (Exception e) {
                log.error("❌ 초기 이벤트 또는 메시지 처리 실패", e);
                // 에러 발생 시 연결이 유효하지 않으므로 정리
                emitters.remove(id);
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    // ========================================================================
    // 2. 메시지 처리 엔드포인트 (PlayMCP가 명령을 보내는 곳)
    // ========================================================================
    @PostMapping("/messages")
    public void handleMessage(@RequestBody String jsonBody) throws IOException {
        JsonNode request = objectMapper.readTree(jsonBody);
        String method = request.path("method").asText();
        JsonNode idNode = request.path("id");

        // 가장 최근에 연결된 Emitter 하나만 가져오기
        SseEmitter emitter = emitters.values().stream().findFirst().orElse(null);
        if (emitter == null) {
            log.warn("⚠️ 연결된 클라이언트가 없습니다. 요청 무시됨.");
            return;
        }

        log.info("📩 MCP 요청 수신: {}", method);

        switch (method) {
            case "initialize":
                JsonNode params = request.path("params");
                handleInitialize(emitter, idNode, params);
                break;
            case "notifications/initialized":
                // 초기화 완료 알림은 그냥 로그만 찍고 넘어감
                log.info("🚀 PlayMCP 초기화 완료됨.");
                break;
            case "tools/list":
                handleToolsList(emitter, idNode);
                break;
            case "tools/call":
                handleToolsCall(emitter, idNode, request);
                break;
            case "ping":
                sendJsonRpcResponse(emitter, idNode, "pong");
                break;
            default:
                log.warn("❓ 알 수 없는 메서드: {}", method);
        }
    }

    // ========================================================================
    // 3. 내부 핸들러 메서드들
    // ========================================================================

    // [핸들러] 초기화 요청 (Handshake)
    private void handleInitialize(SseEmitter emitter, JsonNode id, JsonNode params) throws IOException {
        String clientVersion = params.path("protocolVersion").asText("2025-03-26");
        log.info("protocolVersion : {}", clientVersion);
        sendJsonRpcResponse(emitter, id, Map.of(
                "protocolVersion", clientVersion,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "ServerDoctor-MCP", "version", "1.0.0")
        ));
    }

    // [핸들러] 도구 목록 제공 (여기에 4가지 도구 정의)
    private void handleToolsList(SseEmitter emitter, JsonNode id) throws IOException {
        sendJsonRpcResponse(emitter, id, Map.of(
                "tools", new Object[]{
                        // 1. 서버 진단 (핵심)
                        Map.of(
                                "name", "ServerDoctor-diagnose_server",
                                "description", "특정 서버의 로그와 리소스 상태를 종합 분석하여 장애 원인과 해결책을 진단합니다. 사용자가 '서버 상태 어때?', '왜 에러가 나?'라고 물을 때 사용하세요.",
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "serverName", Map.of("type", "string", "description", "진단할 서버 이름 (예: my-server)")
                                        ),
                                        "required", new String[]{"serverName"}
                                )
                        ),
                        // 2. 로그 조회 (보조)
                        Map.of(
                                "name", "ServerDoctor-fetch_error_logs",
                                "description", "서버에서 최근 발생한 에러 로그들을 조회합니다. 구체적인 에러 메시지가 필요할 때 사용하세요.",
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "serverName", Map.of("type", "string", "description", "대상 서버 이름")
                                        ),
                                        "required", new String[]{"serverName"}
                                )
                        ),
                        // 3. 서버 등록 (사용자 서버 등록)
                        Map.of(
                                "name", "ServerDoctor-register_server",
                                "description", "모니터링할 새로운 대상 서버를 시스템에 등록합니다.",
                                "inputSchema", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "serverName", Map.of("type", "string", "description", "서버 고유 이름"),
                                                "serverUrl", Map.of("type", "string", "description", "서버 URL"),
                                                "healthUrl", Map.of("type", "string", "description", "헬스 체크 URL")
                                        ),
                                        "required", List.of("serverName", "serverUrl")
                                )
                        )
                }
        ));
    }

    // [핸들러] 도구 실행 요청 (실제 로직 연결)
    private void handleToolsCall(SseEmitter emitter, JsonNode id, JsonNode request) throws IOException {
        String toolName = request.path("params").path("name").asText();
        JsonNode args = request.path("params").path("arguments");
        String resultText;
        // 🔍 [디버깅 핵심] Claude가 보낸 인자 전체를 로그로 찍어봅니다!
        log.info("📥 수신된 Tool Name: {}", toolName);
        log.info("📥 수신된 Arguments JSON: {}", args.toPrettyString());

        try {
            if ("ServerDoctor-diagnose_server".equals(toolName)) {
                String serverName = args.path("serverName").asText();
                log.info("🩺 진단 요청: {}", serverName);
                resultText = serverDoctorService.diagnoseForMcp(serverName);

            } else if ("ServerDoctor-fetch_error_logs".equals(toolName)) {
                String serverName = args.path("serverName").asText();
                log.info("📜 로그 조회 요청: {}", serverName);
                ErrorLogAnalysisDto logs = logService.analyzeErrorLogs(serverName);
                resultText = logs.getErrorCount() == 0 ? "발견된 에러 로그가 없습니다." : logs.toString();

            } else if ("ServerDoctor-register_server".equals(toolName)) {
                String serverName = args.path("serverName").asText();
                String serverUrl = args.path("serverUrl").asText(null);
                String healthUrl = args.path("healthUrl").asText(null);

                log.info("📝 서버 등록: {}", serverName);

                // DB 저장
                RegisterServerRequest req = new RegisterServerRequest(serverName, serverUrl, healthUrl);
                serverRegisterService.registerServer(req);

                resultText = String.format("✅ 서버 [%s]가 성공적으로 등록되었습니다. (서버 URL: %s)", serverName, serverUrl);

            } else {
                resultText = "⚠️ 알 수 없는 도구입니다: " + toolName;
            }
        } catch (Exception e) {
            log.error("도구 실행 중 오류", e);
            resultText = "❌ 도구 실행 실패: " + e.getMessage();
        }

        // 결과 전송
        sendJsonRpcResponse(emitter, id, Map.of(
                "content", new Object[]{
                        Map.of("type", "text", "text", resultText)
                }
        ));
    }

    // ========================================================================
    // 4. JSON-RPC 응답 전송 헬퍼
    // ========================================================================
    private void sendJsonRpcResponse(SseEmitter emitter, JsonNode id, Object result) throws IOException {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) response.set("id", id);
        response.putPOJO("result", result);

        emitter.send(SseEmitter.event().name("message").data(response.toString()));
    }
}