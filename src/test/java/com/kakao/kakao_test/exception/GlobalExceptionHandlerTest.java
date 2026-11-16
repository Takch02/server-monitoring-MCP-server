package com.kakao.kakao_test.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void setUp() {
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(events);
        events.stop();
    }

    @Test
    void 예상치못한오류는_서버에_원인스택을_기록하고_응답에는_상세를_노출하지않는다() {
        var result = handler.unknown(new IllegalStateException("diagnostic-only-detail"));

        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isEqualTo(Map.of("error", "INTERNAL_SERVER_ERROR",
                "message", "서버 내부 오류가 발생했습니다."));
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.list.getFirst().getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        assertThat(events.list.getFirst().getThrowableProxy().getMessage()).isEqualTo("diagnostic-only-detail");
    }

    @Test
    void 예외메시지가_null이어도_오류처리중_다시실패하지않는다() {
        var result = handler.unknown(new RuntimeException());

        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isEqualTo(Map.of("error", "INTERNAL_SERVER_ERROR",
                "message", "서버 내부 오류가 발생했습니다."));
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getThrowableProxy()).isNotNull();
    }
}
