package com.kakao.kakao_test.exception;

public class DuplicationServer extends RuntimeException {
    public DuplicationServer(String message) {
        super(message);
    }
}
