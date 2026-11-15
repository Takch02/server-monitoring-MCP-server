package com.kakao.kakao_test.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "diagnostics")
public class DiagnosticProperties {
    @Min(1)
    private long staleSeconds = 60;
    @Min(1)
    private int metricWindowMinutes = 10;
}
