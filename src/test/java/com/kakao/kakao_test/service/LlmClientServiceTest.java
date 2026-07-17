package com.kakao.kakao_test.service;

import com.kakao.kakao_test.dto.OpenAiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_SELF;

@ExtendWith(MockitoExtension.class)
class LlmClientServiceTest {

    @Mock RestClient mockRestClient;

    @InjectMocks LlmClientService llmClientService;

    private RestClient.RequestBodyUriSpec requestSpec;
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(llmClientService, "apiKey", "test-api-key");

        requestSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_SELF);
        responseSpec = mock(RestClient.ResponseSpec.class);

        given(mockRestClient.post()).willReturn(requestSpec);
        given(requestSpec.retrieve()).willReturn(responseSpec);
    }

    @Test
    void 정상응답_content추출() {
        given(responseSpec.body(OpenAiResponse.class)).willReturn(response("AI 진단 결과입니다."));

        String result = llmClientService.analyze("system prompt", "user content");

        assertThat(result).isEqualTo("AI 진단 결과입니다.");
    }

    @Test
    void null응답_기본메시지반환() {
        given(responseSpec.body(OpenAiResponse.class)).willReturn(null);

        String result = llmClientService.analyze("system prompt", "user content");

        assertThat(result).isEqualTo("분석 결과가 없습니다.");
    }

    @Test
    void 빈choices응답_기본메시지반환() {
        OpenAiResponse emptyResponse = new OpenAiResponse();
        ReflectionTestUtils.setField(emptyResponse, "choices", List.of());
        given(responseSpec.body(OpenAiResponse.class)).willReturn(emptyResponse);

        String result = llmClientService.analyze("system prompt", "user content");

        assertThat(result).isEqualTo("분석 결과가 없습니다.");
    }

    @Test
    void HTTP호출실패_에러메시지반환() {
        given(responseSpec.body(any(Class.class))).willThrow(new RuntimeException("Connection refused"));

        String result = llmClientService.analyze("system prompt", "user content");

        assertThat(result).contains("AI 분석 서버 연결 실패");
        assertThat(result).contains("Connection refused");
    }

    // ===== helper =====

    private OpenAiResponse response(String content) {
        OpenAiResponse.Message message = new OpenAiResponse.Message();
        ReflectionTestUtils.setField(message, "content", content);

        OpenAiResponse.Choice choice = new OpenAiResponse.Choice();
        ReflectionTestUtils.setField(choice, "message", message);

        OpenAiResponse res = new OpenAiResponse();
        ReflectionTestUtils.setField(res, "choices", List.of(choice));
        return res;
    }
}
