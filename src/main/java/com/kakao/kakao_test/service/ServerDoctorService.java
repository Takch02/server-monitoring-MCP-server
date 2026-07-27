package com.kakao.kakao_test.service;

import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerDoctorService {

    private final LogService logService;
    private final MetricService metricService;

    /**
     * Claude, PlayMCP 가 이용할 service (AI API 호출 X)
     */
    public String diagnoseForMcp(String serverName, int windowHours) {
        // 1. 데이터 수집
        ErrorLogAnalysisDto logAnalysis = logService.analyzeErrorLogs(serverName, windowHours);
        String metricTrend = metricService.getMetricTrend(serverName);

        // 2. LLM(Claude)이 읽기 좋은 형태로 Raw Data 포맷팅
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### 📊 서버 진단 데이터 (Server: %s, 최근 %dh)\n\n",
                serverName, logAnalysis.getWindowHours()));

        // 리소스 상태
        sb.append("**1. CPU/RAM 상태:**\n").append(metricTrend).append("\n\n");

        // 에러 로그
        sb.append("**2. 최근 에러 로그 분석:**\n");
        if (logAnalysis.isTruncated()) {
            sb.append("- 반환: ").append(logAnalysis.getErrorCount())
              .append("건 — ⚠️ 상한 도달, 실제 에러는 이보다 많음\n");
        } else {
            sb.append("- 에러 수: ").append(logAnalysis.getErrorCount()).append("건\n");
        }

        if (logAnalysis.getErrorCount() > 0) {
            sb.append("\n- 예외 타입 / HTTP 상태 코드 집계:\n")
              .append(logAnalysis.formatAggregation()).append("\n");
            sb.append("- 주요 로그 내역:\n```text\n");
            // 로그 원문을 그대로 Claude에게 전달 (토큰 제한 고려하여 적당히 자르기)
            sb.append(logAnalysis.getRecentErrors().stream()
                    .collect(Collectors.joining("\n")));
            sb.append("\n```\n");
        } else {
            sb.append("- 특이사항: 발견된 에러 로그 없음 (Healthy)\n");
        }

        return sb.toString();
    }
}