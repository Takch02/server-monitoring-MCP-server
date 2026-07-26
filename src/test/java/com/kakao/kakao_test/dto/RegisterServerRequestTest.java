package com.kakao.kakao_test.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterServerRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 정상이름_위반없음() {
        Set<ConstraintViolation<RegisterServerRequest>> violations =
                validator.validate(new RegisterServerRequest("my-server_01"));

        assertThat(violations).isEmpty();
    }

    @Test
    void 빈문자열_위반발생() {
        Set<ConstraintViolation<RegisterServerRequest>> violations =
                validator.validate(new RegisterServerRequest(""));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void 두글자_위반발생() {
        Set<ConstraintViolation<RegisterServerRequest>> violations =
                validator.validate(new RegisterServerRequest("ab"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void 서른한글자_위반발생() {
        Set<ConstraintViolation<RegisterServerRequest>> violations =
                validator.validate(new RegisterServerRequest("a".repeat(31)));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void 특수문자포함_위반발생() {
        Set<ConstraintViolation<RegisterServerRequest>> violations =
                validator.validate(new RegisterServerRequest("my/server"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void 공백포함_위반발생() {
        Set<ConstraintViolation<RegisterServerRequest>> violations =
                validator.validate(new RegisterServerRequest("my server"));

        assertThat(violations).isNotEmpty();
    }
}
