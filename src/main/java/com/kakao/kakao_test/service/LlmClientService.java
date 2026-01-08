package com.kakao.kakao_test.service;

import com.kakao.kakao_test.dto.OpenAiRequest;
import com.kakao.kakao_test.dto.OpenAiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmClientService {

    @Value("${openai.api-key}")
    private String apiKey;

    private final RestClient restClient = RestClient.create();

    public String analyze(String systemPrompt, String userContent) {
        log.info("🤖 LLM에게 분석 요청 중...");

        OpenAiRequest request = OpenAiRequest.builder()
                .model("gpt-4o-mini") // 가성비 모델 (또는 gpt-3.5-turbo)
                .messages(List.of(
                        OpenAiRequest.Message.builder().role("system").content(systemPrompt).build(),
                        OpenAiRequest.Message.builder().role("user").content(userContent).build()
                ))
                .build();

        try {
            OpenAiResponse response = restClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OpenAiResponse.class);

            if (response != null && !response.getChoices().isEmpty()) {
                return response.getChoices().get(0).getMessage().getContent();
            }
        } catch (Exception e) {
            log.error("LLM 호출 실패: {}", e.getMessage());
            return "AI 분석 서버 연결 실패: " + e.getMessage();
        }
        return "분석 결과가 없습니다.";
    }
}