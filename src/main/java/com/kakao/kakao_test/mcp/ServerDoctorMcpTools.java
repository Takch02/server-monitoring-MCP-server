package com.kakao.kakao_test.mcp;

import com.kakao.kakao_test.dto.ErrorLogAnalysisDto;
import com.kakao.kakao_test.dto.RegisterServerRequest;
import com.kakao.kakao_test.dto.RegisterServerResponse;
import com.kakao.kakao_test.service.LogService;
import com.kakao.kakao_test.service.ServerDoctorService;
import com.kakao.kakao_test.service.ServerRegisterService;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServerDoctorMcpTools {

    private final ServerDoctorService serverDoctorService;
    private final LogService logService;
    private final ServerRegisterService serverRegisterService;

    @McpTool(
        name = "ServerDoctor-diagnose_server",
        description = "대상 서버의 최근 에러 로그와 리소스 상태를 조회하여 종합적으로 분석합니다."
    )
    public String diagnoseServer(
        @McpToolParam(description = "진단할 서버 이름", required = true) String serverName
    ) {
        return serverDoctorService.diagnoseForMcp(serverName);
    }

    @McpTool(
        name = "ServerDoctor-fetch_error_logs",
        description = "서버에서 최근 발생한 에러 로그들을 조회 후 분석합니다."
    )
    public String fetchErrorLogs(
        @McpToolParam(description = "대상 서버 이름", required = true) String serverName
    ) {
        ErrorLogAnalysisDto logs = logService.analyzeErrorLogs(serverName);
        return (logs.getErrorCount() == 0) ? "발견된 에러 로그가 없습니다." : logs.toString();
    }

    @McpTool(
        name = "ServerDoctor-register_server",
        description = "모니터링할 새로운 대상 서버를 등록하고, 연동 가이드(yml, env 등)를 생성합니다."
    )
    public String registerServer(
        @McpToolParam(description = "서버 고유 이름", required = true) String serverName,
        @McpToolParam(description = "서버 URL", required = true) String serverUrl
    ) {
        RegisterServerRequest req = new RegisterServerRequest(serverName, serverUrl);
        RegisterServerResponse res = serverRegisterService.registerServer(req);

        return String.format(
            "✅ 서버 [%s]가 성공적으로 등록되었습니다. (서버 URL: %s, IngestToken: %s)\n서버 가이드:\n%s",
            serverName, serverUrl, res.getIngestToken(), res.getGuide()
        );
    }

    @McpTool(
        name = "ServerDoctor-get_setup_guide",
        description = "서버 모니터링을 시작하는 방법을 보여줍니다. Application.yml, docker-compose.yml, .env 설정에 대해 알려줍니다."
    )
    public String getSetupGuide() {
        return serverRegisterService.generateSetupGuide(null, null);
    }
}
