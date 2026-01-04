package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public class RegisterServerRequest {

    private String serverName;
    private String url;
    private String healthPath;
}