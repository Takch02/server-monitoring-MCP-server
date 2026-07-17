package com.kakao.kakao_test.exception;

public class DuplicationServerException extends RuntimeException {
    public DuplicationServerException(String message) {
        super(message);
    }
}
