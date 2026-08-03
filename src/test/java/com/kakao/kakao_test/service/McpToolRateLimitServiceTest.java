package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.McpToolRateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRateLimitServiceTest {

    private MutableClock clock;
    private McpToolRateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        McpToolRateLimitProperties properties = new McpToolRateLimitProperties();
        properties.setTools(Map.of(
                "diagnose_server", new McpToolRateLimitProperties.ToolLimit(60, 2)
        ));
        clock = new MutableClock(Instant.EPOCH);
        rateLimitService = new McpToolRateLimitService(properties, clock);
    }

    @Test
    void burst한도까지_허용하고_다음요청은_거절한다() {
        assertThat(rateLimitService.tryAcquire("diagnose_server").allowed()).isTrue();
        assertThat(rateLimitService.tryAcquire("diagnose_server").allowed()).isTrue();

        McpToolRateLimitService.RateLimitResult result = rateLimitService.tryAcquire("diagnose_server");

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void serverName과무관하게_같은도구의전역한도를공유한다() {
        rateLimitService.tryAcquire("diagnose_server"); // serverName=first-server 가정
        rateLimitService.tryAcquire("diagnose_server"); // serverName=second-server 가정

        assertThat(rateLimitService.tryAcquire("diagnose_server").allowed()).isFalse();
    }

    @Test
    void refill후_다시호출할수있다() {
        rateLimitService.tryAcquire("diagnose_server");
        rateLimitService.tryAcquire("diagnose_server");
        clock.advanceSeconds(1);

        assertThat(rateLimitService.tryAcquire("diagnose_server").allowed()).isTrue();
    }

    @Test
    void 도구별버킷은서로분리된다() {
        rateLimitService.tryAcquire("diagnose_server");
        rateLimitService.tryAcquire("diagnose_server");

        assertThat(rateLimitService.tryAcquire("get_health_status").allowed()).isTrue();
    }

    private static class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
