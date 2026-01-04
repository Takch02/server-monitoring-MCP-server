package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.exception.NotFoundException;
import com.kakao.kakao_test.exception.UnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unknown(Exception e) {
        return ResponseEntity.status(500).body(Map.of(
                "error", "INTERNAL_SERVER_ERROR",
                "message", e.getMessage()
        ));
    }
}
