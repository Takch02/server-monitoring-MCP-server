package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class HealthCheckDto {
    private String serverName;
    private String url;
    private boolean success;
    private String details;
}
