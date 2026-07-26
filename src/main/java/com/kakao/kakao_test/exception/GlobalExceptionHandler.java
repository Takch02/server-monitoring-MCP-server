package com.kakao.kakao_test.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
        return ResponseEntity.status(500).body(Map.of(
                "error", "INTERNAL_SERVER_ERROR",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler({IOException.class})
    public void handleClientAbort() {
        log.debug("SSE 연결 끊어짐");
    }
}
