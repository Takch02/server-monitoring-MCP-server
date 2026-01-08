package com.kakao.kakao_test.controller;// DoctorController.java

import com.kakao.kakao_test.service.ServerDoctorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DoctorController {

    private final ServerDoctorService serverDoctorService;

    // 브라우저에서 링크 클릭으로 호출되므로 GET으로 받음
    @GetMapping("/servers/{name}/diagnose")
    public String triggerDiagnosis(
            @PathVariable("name") String serverName,
            @RequestParam("webhook") String webhookUrl) { // URL 파라미터로 웹훅 주소를 받음

        // 비동기 분석 시작
        serverDoctorService.diagnoseAndReport(serverName, webhookUrl);

        // 브라우저에 보여줄 간단한 응답 (HTML)
        return """
            <html>
            <body style="text-align:center; padding-top:50px;">
                <h1>🕵️‍♂️ AI 분석이 시작되었습니다!</h1>
                <p>브라우저를 닫고 디스코드를 확인해주세요.</p>
                <script>window.close();</script> </body>
            </html>
            """;
    }
}