package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ServerMetricsDto {
    private double cpuUsagePercent; // 0~100
    private double jvmMemoryUsedMb;
    private double jvmMemoryMaxMb;
    private boolean success;
}
