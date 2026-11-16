package com.kakao.kakao_test.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> invalidRequest(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(400).body(Map.of(
                "error", "INVALID_REQUEST",
                "message", message
        ));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> malformedRequest(Exception error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_REQUEST",
                "message", "요청 헤더 또는 본문 형식을 확인하십시오."
        ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(Map.of(
                "error", "UNSUPPORTED_MEDIA_TYPE",
                "message", "지원하지 않는 요청 본문 형식입니다."
        ));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> notFound(NotFoundException e) {
        return ResponseEntity.status(404).body(Map.of(
                "error", "NOT_FOUND",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<?> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(401).body(Map.of(
                "error", "UNAUTHORIZED",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(DuplicationServerException.class)
    public ResponseEntity<?> duplication(DuplicationServerException e) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "CONFLICT",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unknown(Exception e) {
        log.error("서버 요청 처리 중 예기치 않은 오류가 발생했습니다.", e);
        return ResponseEntity.status(500).body(Map.of(
                "error", "INTERNAL_SERVER_ERROR",
                "message", "서버 내부 오류가 발생했습니다."
        ));
    }

    @ExceptionHandler({IOException.class})
    public void handleClientAbort() {
        log.debug("SSE 연결 끊어짐");
    }
}
