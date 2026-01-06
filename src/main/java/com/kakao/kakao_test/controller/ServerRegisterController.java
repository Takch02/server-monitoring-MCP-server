package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.dto.*;
import com.kakao.kakao_test.service.LogService;
import com.kakao.kakao_test.service.ServerRegisterService;
import jdk.jfr.Description;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Description("사용자 서버를 등록하는 앤드포인트")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ServerRegisterController {

    private final LogService logService;
    private final ServerRegisterService serverRegisterService;

    // 서버 등록
    @PostMapping("/servers")
    public RegisterServerResponse register(@RequestBody RegisterServerRequest req) {
        return serverRegisterService.registerServer(req);
    }

    // URL 갱신 등
    @PatchMapping("/servers/{name}/url")
    public void updateUrl(@PathVariable String name, @RequestBody UpdateServerUrlRequest req) {
        serverRegisterService.updateServerUrl(name, req.getUrl());
    }


    // 재시작(데모)
    @PostMapping("/servers/{name}/actions/restart")
    public RestartResultDto restart(@PathVariable String name, @RequestBody RestartRequest req) {
        return logService.restartServer(name, req);
    }
}
