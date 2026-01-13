package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.dto.HealthIngestDto;
import com.kakao.kakao_test.dto.IngestResultDto;
import com.kakao.kakao_test.dto.LogEventDto;
import com.kakao.kakao_test.dto.MetricIngestDto;
import com.kakao.kakao_test.service.HealthService;
import com.kakao.kakao_test.service.LogService;
import com.kakao.kakao_test.service.MetricService;
import jdk.jfr.Description;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Description("Forwarder 가 수집한 logs, metrics 를 받는 앤드포인트")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class IngestController {

    private final MetricService metricService;
    private final LogService logService;
    private final HealthService healthService;

    // 로그 수신(PUSH): 포워더/사용자 서버가 호출
    @PostMapping("/servers/{name}/ingest/logs")
    public IngestResultDto ingestLogs(@PathVariable("name") String serverName,
                                      @RequestHeader("X-MCP-TOKEN") String token,
                                      @RequestBody List<LogEventDto> events,
                                      @RequestHeader(value = "X-DISCORD-WEBHOOK-URL", required = false) String discordWebhookUrl) {
        return logService.ingestLogs(serverName, token, discordWebhookUrl, events);
    }


    @PostMapping("/servers/{serverName}/ingest/metrics")
    public ResponseEntity<String> ingestMetrics(
            @PathVariable String serverName,
            @RequestHeader("X-MCP-TOKEN") String token,
            @RequestHeader(value = "X-DISCORD-WEBHOOK-URL", required = false) String discordWebhookUrl,
            @RequestBody MetricIngestDto dto) {

        metricService.saveMetric(serverName, dto, token, discordWebhookUrl);

        return ResponseEntity.ok("ok");
    }

    @PostMapping("/servers/{serverName}/ingest/health")
    public ResponseEntity<String> ingestHealth(
            @PathVariable String serverName,
            @RequestHeader("X-MCP-TOKEN") String token,
            @RequestBody HealthIngestDto dto) {

        healthService.saveHealth(serverName, dto, token);
        return ResponseEntity.ok("ok");
    }
}
