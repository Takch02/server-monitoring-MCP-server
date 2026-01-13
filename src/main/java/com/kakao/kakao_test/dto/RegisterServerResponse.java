package com.kakao.kakao_test.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RegisterServerResponse {
    private String serverName;
    private String url;
    private String ingestToken; // 최초 1회 전달(데모)
    private String guide;
}
