package com.kakao.kakao_test.service;

import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerDoctorService {

    private final LogService logService;
    private final MetricService metricService;
    private final LlmClientService llmClientService;
    private final DiscordNotificationService discordService;

    /**
     * [CASE 1] 디스코드 링크 클릭 등으로 요청 (비동기)
     * - 결과는 디스코드 웹훅으로 전송
     */
    @Async
    public void diagnoseAndReport(String serverName, String discordWebhookUrl) {
        // 1. 진단 리포트 생성
        String report = generateDiagnosisReport(serverName);

        // 2. 디스코드로 전송
        if (discordWebhookUrl != null && !discordWebhookUrl.isBlank()) {
            discordService.sendAiReport(discordWebhookUrl, report);
        }
    }

    /**
     * [CASE 2] MCP(PlayMCP) 채팅에서 요청 (동기)
     * - 결과는 문자열로 즉시 반환 (Controller가 SSE로 전송)
     */
    public String diagnoseForMcp(String serverName) {
        // 1. 진단 리포트 생성 후 즉시 리턴
        return generateDiagnosisReport(serverName);
    }

    /**
     * [핵심 로직] 로그 분석 및 LLM 리포트 생성 (전송 로직 제거됨)
     * - 순수하게 "String"만 만들어서 리턴함
     */
    private String generateDiagnosisReport(String serverName) {
        // 1. 데이터 수집
        ErrorLogAnalysisDto logAnalysis = logService.analyzeErrorLogs(serverName);
        String metricTrend = metricService.getMetricTrend(serverName);

        // 2. 조기 종료 조건: 서버가 너무 건강할 때 (LLM 비용 절약)
        if (logAnalysis.getErrorCount() == 0 && metricTrend.contains("안정적")) {
            return "✅ **[진단 결과]**\n현재 서버 상태가 매우 안정적입니다.\n- 발견된 에러 로그 없음\n- 리소스 사용량 정상 범위\n(특이사항이 없어 상세 분석을 생략합니다.)";
        }

        // 3. 프롬프트 구성
        String systemPrompt = """
        당신은 시스템 로그를 분석하여 장애 원인을 파악하는 'DevOps 포렌식 전문가'입니다.
        주어지는 '에러 로그'와 '리소스 상태'를 근거로, 현재 서버에 발생한 구체적인 문제를 분석하세요.

        [분석 규칙]
        1. 추측성 발언 금지: "DDoS 공격일 수 있습니다" 같은 막연한 말 대신, 로그에 있는 구체적인 Exception 이름과 메시지를 언급하며 설명하세요.
        2. 근거 기반: "로그를 보니 [NullPointerException]이 발생했고, 이는 [UserSerivce] 로직의 버그로 보입니다." 처럼 말하세요.
        3. 해결책: 코드 레벨에서 수정해야 할 부분이나, 당장 실행해야 할 조치를 구체적으로 제시하세요.
        4. 데이터가 부족하거나 특이사항이 없다면 솔직하게 "분석할 만한 치명적인 에러가 발견되지 않았습니다."라고 말하세요.
        5. 한국어로 답변하세요.
        """;

        String userContent = String.format("""
        [분석 요청 데이터]
        1. 서버명: %s
        2. CPU/RAM 상태: %s
        3. 최근 발생한 핵심 에러 로그 (최대 10건):
        ```text
        %s
        ```
        (위 로그를 바탕으로 원인을 추적해주세요.)
        """,
                serverName,
                metricTrend,
                logAnalysis.getRecentErrors().stream()
                        .limit(10) // 토큰 절약을 위해 10개만
                        .collect(Collectors.joining("\n"))
        );

        // 4. LLM 호출
        String aiAnalysis = llmClientService.analyze(systemPrompt, userContent);

        // 5. 최종 포맷팅
        return "## 🤖 AI 서버 주치의 진단 리포트\n" + aiAnalysis;
    }
}