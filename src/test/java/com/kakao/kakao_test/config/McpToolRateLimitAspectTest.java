package com.kakao.kakao_test.config;

import com.kakao.kakao_test.service.McpToolRateLimitService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.then;

class McpToolRateLimitAspectTest {

    @Test
    void 허용된도구호출은_원래결과를반환한다() throws Throwable {
        McpToolRateLimitService rateLimitService = mock(McpToolRateLimitService.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        McpTool mcpTool = mock(McpTool.class);
        McpToolRateLimitAspect aspect = new McpToolRateLimitAspect(rateLimitService);
        given(mcpTool.name()).willReturn("diagnose_server");
        given(rateLimitService.tryAcquire("diagnose_server"))
                .willReturn(McpToolRateLimitService.RateLimitResult.permit());
        given(joinPoint.proceed()).willReturn("진단 결과");

        Object result = aspect.limit(joinPoint, mcpTool);

        assertThat(result).isEqualTo("진단 결과");
        then(joinPoint).should().proceed();
    }

    @Test
    void 한도초과면_조회없이_재시도안내를반환한다() throws Throwable {
        McpToolRateLimitService rateLimitService = mock(McpToolRateLimitService.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        McpTool mcpTool = mock(McpTool.class);
        McpToolRateLimitAspect aspect = new McpToolRateLimitAspect(rateLimitService);
        given(mcpTool.name()).willReturn("diagnose_server");
        given(rateLimitService.tryAcquire("diagnose_server"))
                .willReturn(McpToolRateLimitService.RateLimitResult.reject(4));

        Object result = aspect.limit(joinPoint, mcpTool);

        assertThat(result).isEqualTo("⚠️ 현재 diagnose_server 도구 호출이 많아 요청을 처리하지 않았습니다. 4초 후 한 번만 다시 호출하세요.");
        then(joinPoint).shouldHaveNoInteractions();
    }
}
