package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.HealthCheckDto;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
@RequiredArgsConstructor
public class HealthCheckService {

    // 외부 HTTP 요청용 클라이언트
    private final RestClient restClient = RestClient.create();
    private final TargetServerRepository targetServerRepository;
    /**
     * 사용자 서버 헬스체크
     * (주의: TargetServer Entity에 URL 필드가 있다고 가정하고 작성)
     */
    public HealthCheckDto checkHealth(String name) {
        TargetServer server = targetServerRepository.getByServerName(name);
        String baseUrl = server.getServerUrl();
        String healthPath = server.getHealthPath();
        String fullUrl = baseUrl + healthPath;

        try {
            String result = restClient.get()
                    .uri(fullUrl)
                    .retrieve()
                    .body(String.class);

            return new HealthCheckDto(server.getServerName(), fullUrl, true, result);

        } catch (Exception e) {
            return new HealthCheckDto(server.getServerName(), fullUrl, false, "연결 실패: " + e.getMessage());
        }
    }
}
