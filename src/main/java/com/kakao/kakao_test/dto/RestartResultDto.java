package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RestartResultDto {
    private String serverName;
    private boolean success;
    private String message;
}
