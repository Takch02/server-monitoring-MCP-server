package com.kakao.kakao_test.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LogEventDto {
    private long ts;        // epoch millis
    private String level;   // INFO/WARN/ERROR
    private String message; // 한 줄 메시지(마스킹된 형태 권장)
}
