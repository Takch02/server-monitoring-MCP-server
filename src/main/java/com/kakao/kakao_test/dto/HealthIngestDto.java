package com.kakao.kakao_test.dto;

public record HealthIngestDto(
    long ts,
    String status,        // "UP" / "DOWN" / "DEGRADED" 등
    long latencyMs,
    int httpStatus,
    String message        // 에러나 요약
) {}