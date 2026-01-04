package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ErrorLogAnalysisDto {
    private String serverName;
    private List<String> recentErrors;
    private int errorCount;
    private String summary;
}
