package com.kakao.kakao_test.config;

import com.kakao.kakao_test.service.McpToolRateLimitService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class McpToolRateLimitAspect {

    private final McpToolRateLimitService rateLimitService;

    @Around("@annotation(mcpTool)")
    public Object limit(ProceedingJoinPoint joinPoint, McpTool mcpTool) throws Throwable {
        McpToolRateLimitService.RateLimitResult result = rateLimitService.tryAcquire(mcpTool.name());
        if (result.allowed()) {
            return joinPoint.proceed();
        }

        return "⚠️ 현재 " + mcpTool.name() + " 도구 호출이 많아 요청을 처리하지 않았습니다. "
                + result.retryAfterSeconds() + "초 후 한 번만 다시 호출하세요.";
    }
}
