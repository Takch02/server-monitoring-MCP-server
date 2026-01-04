package com.kakao.kakao_test.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class RestartRequest {
    private boolean dryRun;       // true면 실행 대신 시뮬레이션
    private String confirmToken;  // 데모: "CONFIRM" 같은 문자열로 확인
}
