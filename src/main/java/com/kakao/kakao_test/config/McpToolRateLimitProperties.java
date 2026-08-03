package com.kakao.kakao_test.config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * application.yml 의 설정 값을 읽고 Tool 마다 제한 값을 세팅함.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mcp.tool-rate-limit")
public class McpToolRateLimitProperties {

    private int defaultPermitsPerMinute = 30;
    private int defaultBurstCapacity = 5;
    private Map<String, ToolLimit> tools = new HashMap<>();

    public ToolLimit getLimit(String toolName) {
        return tools.getOrDefault(toolName, new ToolLimit(defaultPermitsPerMinute, defaultBurstCapacity));
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolLimit {
        private int permitsPerMinute;
        private int burstCapacity;
    }
}
