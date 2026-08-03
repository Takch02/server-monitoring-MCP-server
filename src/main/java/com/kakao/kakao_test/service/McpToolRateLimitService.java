package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.McpToolRateLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class McpToolRateLimitService {

    private static final long ONE_MINUTE_MILLIS = 60_000L;

    private final McpToolRateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitResult tryAcquire(String toolName) {
        TokenBucket bucket = buckets.computeIfAbsent(toolName, this::createBucket);
        return bucket.tryAcquire(clock.millis());
    }

    private TokenBucket createBucket(String toolName) {
        McpToolRateLimitProperties.ToolLimit limit = properties.getLimit(toolName);
        return new TokenBucket(limit.getPermitsPerMinute(), limit.getBurstCapacity());
    }

    public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

        public static RateLimitResult permit() {
            return new RateLimitResult(true, 0);
        }

        public static RateLimitResult reject(long retryAfterSeconds) {
            return new RateLimitResult(false, retryAfterSeconds);
        }
    }

    private static class TokenBucket {

        private final int permitsPerMinute;
        private final int burstCapacity;
        private double tokens;
        private long lastRefillMillis = -1;

        private TokenBucket(int permitsPerMinute, int burstCapacity) {
            if (permitsPerMinute <= 0 || burstCapacity <= 0) {
                throw new IllegalArgumentException("MCP 도구 호출 제한 값은 0보다 커야 합니다.");
            }
            this.permitsPerMinute = permitsPerMinute;
            this.burstCapacity = burstCapacity;
            this.tokens = burstCapacity;
        }

        private synchronized RateLimitResult tryAcquire(long nowMillis) {
            refill(nowMillis);
            if (tokens >= 1) {
                tokens--;
                return RateLimitResult.permit();
            }

            double tokensPerSecond = (double) permitsPerMinute / ONE_MINUTE_MILLIS * 1_000;
            long retryAfterSeconds = Math.max(1, (long) Math.ceil((1 - tokens) / tokensPerSecond));
            return RateLimitResult.reject(retryAfterSeconds);
        }

        private void refill(long nowMillis) {
            if (lastRefillMillis < 0) {
                lastRefillMillis = nowMillis;
                return;
            }

            long elapsedMillis = Math.max(0, nowMillis - lastRefillMillis);
            tokens = Math.min(burstCapacity, tokens + (double) elapsedMillis * permitsPerMinute / ONE_MINUTE_MILLIS);
            lastRefillMillis = nowMillis;
        }
    }
}
