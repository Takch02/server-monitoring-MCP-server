package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class IngestResultDto {
    private String serverName;
    private int acceptedCount;
    private String message;
}
