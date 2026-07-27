package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
@AllArgsConstructor
public class ErrorLogAnalysisDto {
    private String serverName;
    private List<String> recentErrors;
    private int errorCount;
    private String summary;
    private int windowHours;
    private boolean truncated;
    private Map<String, Long> exceptionTypeCounts;
    private Map<String, Long> httpStatusCounts;

    /**
     * 예외 타입별 / HTTP 상태 코드별 집계를 사람이 읽기 좋은 텍스트로 변환
     */
    public String formatAggregation() {
        StringBuilder sb = new StringBuilder();
        sb.append(formatCounts("**[예외 타입별 집계]**", exceptionTypeCounts, "(집계할 예외 타입 없음)"));
        sb.append("\n");
        sb.append(formatCounts("**[HTTP 상태 코드별 집계]**", httpStatusCounts, "(감지된 HTTP 상태 코드 없음)"));
        return sb.toString();
    }

    private String formatCounts(String title, Map<String, Long> counts, String emptyMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n");
        if (counts == null || counts.isEmpty()) {
            sb.append(emptyMessage).append("\n");
        } else {
            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("건\n"));
        }
        return sb.toString();
    }

    /**
     * MCP(Claude)에게 보낼 Raw Data 포맷으로 변환
     * LLM이 읽기 편하도록 Markdown 형식과 코드 블록을 사용합니다.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        // 1. 헤더 및 요약 정보
        sb.append(String.format("### 📊 에러 로그 데이터 (Server: %s, 최근 %dh)\n", serverName, windowHours));
        if (truncated) {
            sb.append(String.format("- 🚨 반환: %d건 (최근 %dh) — ⚠️ 상한 도달, 실제 에러는 이보다 많음\n", errorCount, windowHours));
        } else {
            sb.append(String.format("- 🚨 에러 수: %d건 (최근 %dh)\n", errorCount, windowHours));
        }

        if (summary != null && !summary.isBlank()) {
            sb.append(String.format("- 📝 상태 요약: %s\n", summary));
        }
        sb.append("\n");

        // 1.5. 예외 타입 / 상태 코드 집계 (원문보다 먼저 보여줘서 신호를 먼저 전달)
        if (errorCount > 0) {
            sb.append(formatAggregation()).append("\n");
        }

        // 2. 에러 로그 본문 (LLM이 텍스트로 인식하도록 코드 블록 처리)
        sb.append("**[최근 발생한 에러 로그 목록]**\n");

        if (recentErrors == null || recentErrors.isEmpty()) {
            sb.append("(최근 발견된 에러 로그가 없습니다. 서버가 건강합니다.)");
        } else {
            sb.append("```text\n"); // 로그는 text 코드 블록으로 감싸야 AI가 분석하기 좋음

            // 리스트의 각 에러를 줄바꿈으로 연결
            String joinedLogs = recentErrors.stream()
                    .collect(Collectors.joining("\n----------------------------------------\n"));

            sb.append(joinedLogs);
            sb.append("\n```");
        }

        return sb.toString();
    }
}
