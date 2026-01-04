package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.dto.IngestResultDto;
import com.kakao.kakao_test.dto.LogEventDto;
import com.kakao.kakao_test.dto.MetricIngestDto;
import com.kakao.kakao_test.service.McpService;
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
    private final McpService mcpService;

    // 로그 수신(PUSH): 포워더/사용자 서버가 호출
    @PostMapping("/servers/{name}/ingest/logs")
    public IngestResultDto ingestLogs(@PathVariable("name") String serverName,
                                      @RequestHeader("X-MCP-TOKEN") String token,
                                      @RequestBody List<LogEventDto> events) {
        return mcpService.ingestLogs(serverName, token, events);
    }


    @PostMapping("/servers/{serverName}/ingest/metrics")
    public ResponseEntity<String> ingestMetrics(
            @PathVariable String serverName,
            @RequestHeader("X-MCP-TOKEN") String token,
            @RequestBody MetricIngestDto dto) {

        metricService.saveMetric(serverName, dto, token);

        return ResponseEntity.ok("ok");
    }
}
