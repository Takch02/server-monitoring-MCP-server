package com.kakao.kakao_test.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class OpenAiRequest {
    private String model;
    private List<Message> messages;

    @Getter @Builder
    public static class Message {
        private String role; // "system", "user"
        private String content;
    }
}