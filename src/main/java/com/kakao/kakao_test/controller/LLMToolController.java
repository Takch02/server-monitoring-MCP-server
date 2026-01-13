package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import com.kakao.kakao_test.dto.ServerMetricsDto;
import com.kakao.kakao_test.service.LogService;
import com.kakao.kakao_test.service.MetricService;
import jdk.jfr.Description;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Description("LLM 모델이 이용할 앤드포인트(도구)")
@RestController
@RequestMapping("/tool")
@RequiredArgsConstructor
public class LLMToolController {

    private final LogService logService;
    private final MetricService metricService;

    /**
     * 최근 [ERROR] 발생 로그를 반환
     */
    @GetMapping("/servers/{name}/errors")
    public ResponseEntity<ErrorLogAnalysisDto> errors(@PathVariable String name){
        return ResponseEntity.ok(logService.analyzeErrorLogs(name));
    }

    /**
     * 현재 Metrics 정보를 가져옴
     */
    @Description("현재 Metrics 상태를 반환")
    @GetMapping("/servers/{name}/metrics")
    public ResponseEntity<ServerMetricsDto> getCurrentMetrics(@PathVariable String name) {
        return ResponseEntity.ok(metricService.getCurrentMetrics(name));
    }

    /**
     * 최근 Metrics 을 분석하여 반환
     */
    @Description("최근 Metrics 을 분석하여 반환")
    @GetMapping("/servers/{name}/trend-metrics")
    public ResponseEntity<String> getMetricTrend(@PathVariable String name) {
        return ResponseEntity.ok(metricService.getMetricTrend(name));
    }

}
