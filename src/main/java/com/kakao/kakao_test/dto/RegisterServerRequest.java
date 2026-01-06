package com.kakao.kakao_test.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public class RegisterServerRequest {

    @NotNull
    @NotBlank
    @Size(min = 3, max = 30)
    private String serverName;


    @NotNull
    @NotBlank
    private String url;

    private String healthPath;
}