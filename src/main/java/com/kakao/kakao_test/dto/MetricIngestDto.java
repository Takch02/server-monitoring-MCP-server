package com.kakao.kakao_test.dto;
import lombok.Data;

@Data
public class MetricIngestDto {
    private String serverName;
    private Long ts;           // 타임스탬프
    private String type;       // "METRIC"
    private MetricData data;   // 상세 데이터 객체
    private String eventId;

    @Data
    public static class MetricData {
        private double cpuUsage;      // 0.0 ~ 1.0
        private double memoryUsed;    // bytes
        private double memoryMax;     // bytes
        private double memoryPercent; // 0.0 ~ 100.0
    }
}