package com.kakao.kakao_test.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import com.kakao.kakao_test.dto.RegisterServerRequest;
import com.kakao.kakao_test.dto.RegisterServerResponse;
import com.kakao.kakao_test.service.LogService;
import com.kakao.kakao_test.service.ServerDoctorService;
import com.kakao.kakao_test.service.ServerRegisterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@RestController
@RequestMapping("/legacy")
@RequiredArgsConstructor
public class McpController {

    private final ServerDoctorService serverDoctorService;
    private final LogService logService;
    private final ServerRegisterService serverRegisterService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 현재 활성화된 단일 Emitter 관리
    private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    /**
     * MCP 가 접속하는 EndPoint
     */
    @CrossOrigin(origins = "*")
    @RequestMapping(
            value = "/sse",
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<SseEmitter> connect(@RequestBody(required = false) String body) {
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(0L); // 0L = no timeout (Spring 관례)

        emitters.put(sessionId, emitter);

        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        emitter.onError((e) -> emitters.remove(sessionId));
        log.info("🔌 PlayMCP 연결됨 (Session ID: {})", sessionId);


        // 2. 비동기 스레드에서 이벤트 및 초기화 메시지 처리
        new Thread(() -> {
            try {
                emitter.send(SseEmitter.event().name("endpoint").data("messages?sessionId=" + sessionId));
                log.info("✅ Endpoint 이벤트 전송 완료");

                // 요청 Body에 'initialize' 메시지가 있었다면 즉시 처리
                if (body != null && !body.isEmpty() && !body.equals("{}")) {
                    log.info("📩 연결 요청에 포함된 메시지 처리 중...");
                    handleMessage(body, sessionId); // 기존 handleMessage 메서드 재사용
                }

            } catch (ClientAbortException e) {
                log.warn("❌ 클라이언트가 연결을 끊음 (Endpoint 전송 중)");
            }
            catch (Exception e) {
                log.error("❌ 초기 이벤트 또는 메시지 처리 실패", e);
                emitters.remove(sessionId);
                emitter.completeWithError(e);
            }
        }).start();

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache, no-transform") // 캐싱 방지
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /**
     * MCP 가 명령을 보내는 Endpoint
     */
    @PostMapping("/messages")
    @CrossOrigin(origins = "*")
    public ResponseEntity<Void> handleMessage(@RequestBody String jsonBody, @RequestParam("sessionId") String sessionId) {
        try {
            SseEmitter emitter = emitters.get(sessionId);
            JsonNode request = objectMapper.readTree(jsonBody);
            String method = request.path("method").asText();
            JsonNode idNode = request.get("id");

            if (emitter == null) {
                log.warn("⚠️ 연결된 클라이언트가 없습니다. 요청 무시됨.");
                return ResponseEntity.notFound().build();
            }

            log.info("📩 MCP 요청 수신: {}", method);

            switch (method) {
                case "initialize":
                    JsonNode params = request.path("params");
                    handleInitialize(emitter, idNode, params);
                    break;
                case "notifications/initialized":
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
                    break;

            }
            return ResponseEntity.accepted().build();
        } catch (Exception e) {
            log.error("❌ 메시지 처리 중 오류 발생 (무시함): {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }


    /**
     * 초기화 요청 메소드
     */
    private void handleInitialize(SseEmitter emitter, JsonNode id, JsonNode params) {
        String clientVersion = params.path("protocolVersion").asText("2025-03-26");
        log.info("Client Protocol Version: {}", clientVersion);

        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", clientVersion);

        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", true)
        ));

        result.put("serverInfo", Map.of(
                "name", "SpringServerDoctor",
                "version", "1.0.0"
        ));

        sendJsonRpcResponse(emitter, id, result);
    }

    // [핸들러] 도구 목록 제공 (여기에 4가지 도구 정의)
    private void handleToolsList(SseEmitter emitter, JsonNode id) {
        // 도구 목록 정의
        List<Map<String, Object>> tools = List.of(
                // 1. 서버 진단
                Map.of(
                        "name", "ServerDoctor_diagnose_server",
                        "description", "대상 서버의 최근 에러 로그와 리소스 상태를 조회합니다.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("serverName", Map.of("type", "string", "description", "진단할 서버 이름")),
                                "required", List.of("serverName")
                        )
                ),
                // 2. 로그 조회
                Map.of(
                        "name", "ServerDoctor_fetch_error_logs",
                        "description", "서버에서 최근 발생한 에러 로그들을 조회합니다.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("serverName", Map.of("type", "string", "description", "대상 서버 이름")),
                                "required", List.of("serverName")
                        )
                ),
                // 3. 서버 등록
                Map.of(
                        "name", "ServerDoctor_register_server",
                        "description", "모니터링할 새로운 대상 서버를 등록하고, 연동 가이드(yml, env 등)를 생성합니다.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "serverName", Map.of("type", "string", "description", "서버 고유 이름"),
                                        "serverUrl", Map.of("type", "string", "description", "서버 URL"),
                                        "healthUrl", Map.of("type", "string", "description", "헬스 체크 URL")
                                ),
                                "required", List.of("serverName", "serverUrl")
                        )
                ),
                // 4. 가이드 조회
                Map.of(
                        "name", "ServerDoctor_get_setup_guide",
                        "description", "모니터링 연동 템플릿을 조회합니다. 반환된 코드를 요약 없이 그대로 보여주세요.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "required", List.of()
                        )
                )
        );

        sendJsonRpcResponse(emitter, id, Map.of("tools", tools));
    }

    // [핸들러] 도구 실행 요청 (실제 로직 연결)
    private void handleToolsCall(SseEmitter emitter, JsonNode id, JsonNode request) {
        String toolName = request.path("params").path("name").asText();
        JsonNode args = request.path("params").path("arguments");
        String resultText;

        log.info("📥 수신된 Tool Name: {}", toolName);

        try {
            if ("ServerDoctor_diagnose_server".equals(toolName)) {
                String serverName = args.path("serverName").asText();
                log.info("🩺 진단 요청: {}", serverName);
                resultText = serverDoctorService.diagnoseForMcp(serverName);

            } else if ("ServerDoctor_fetch_error_logs".equals(toolName)) {
                String serverName = args.path("serverName").asText();
                log.info("📜 로그 조회 요청: {}", serverName);
                ErrorLogAnalysisDto logs = logService.analyzeErrorLogs(serverName);
                resultText = logs.getErrorCount() == 0 ? "발견된 에러 로그가 없습니다." : logs.toString();

            } else if ("ServerDoctor_register_server".equals(toolName)) {
                String serverName = args.path("serverName").asText();
                String serverUrl = args.path("serverUrl").asText(null);

                log.info("📝 서버 등록: {}", serverName);

                // DB 저장
                RegisterServerRequest req = new RegisterServerRequest(serverName, serverUrl);
                RegisterServerResponse res = serverRegisterService.registerServer(req);

                resultText = String.format("✅ 서버 [%s]가 성공적으로 등록되었습니다. (서버 URL: %s, IngestToken : %s)\n" +
                        "서버 가이드 : %s", serverName, serverUrl, res.getIngestToken(), res.getGuide());

            }
            else if ("ServerDoctor_get_setup_guide".equals(toolName)) {
                log.info("서버 등록 가이드라인 요청");
                resultText = serverRegisterService.generateSetupGuide(null, null);
            }
            else {
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
    private void sendJsonRpcResponse(SseEmitter emitter, JsonNode requestId, Object result) {
        if (emitter == null) return;

        try {
            // 1. 응답 맵 구성
            Map<String, Object> response = new HashMap<>();
            response.put("jsonrpc", "2.0");
            if (requestId != null) {
                response.put("id", requestId); // 숫자형/문자형 모두 처리 가능하도록 Object로 넣음
            }
            response.put("result", result);

            String jsonString = objectMapper.writeValueAsString(response);

            // 3. 전송
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(jsonString));

            log.info("✅ 응답 전송 완료");

        } catch (Exception e) {
            log.warn("❌ JsonRpc 에러 : ", e);
        }
    }
}