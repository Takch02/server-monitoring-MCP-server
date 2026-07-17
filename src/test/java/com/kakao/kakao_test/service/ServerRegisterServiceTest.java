package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.RegisterServerRequest;
import com.kakao.kakao_test.dto.RegisterServerResponse;
import com.kakao.kakao_test.exception.DuplicationServerException;
import com.kakao.kakao_test.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ServerRegisterServiceTest {

    @Mock TargetServerRepository targetServerRepository;

    @InjectMocks ServerRegisterService serverRegisterService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(serverRegisterService, "mcpDomain", "https://mcp.test.com");
        ReflectionTestUtils.setField(serverRegisterService, "forwarderDomain", "ghcr.io/test/forwarder:latest");
    }

    // ===== registerServer =====

    @Test
    void 중복서버이름_DuplicationServerException발생() {
        given(targetServerRepository.existsByServerName("my-server")).willReturn(true);

        assertThatThrownBy(() ->
                serverRegisterService.registerServer(new RegisterServerRequest("my-server"))
        ).isInstanceOf(DuplicationServerException.class)
                .hasMessageContaining("my-server");
    }

    @Test
    void 정상등록_UUID토큰발급() {
        given(targetServerRepository.existsByServerName("my-server")).willReturn(false);

        RegisterServerResponse response = serverRegisterService.registerServer(new RegisterServerRequest("my-server"));

        assertThat(response.getIngestToken())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void 정상등록_서버이름응답에포함() {
        given(targetServerRepository.existsByServerName("my-server")).willReturn(false);

        RegisterServerResponse response = serverRegisterService.registerServer(new RegisterServerRequest("my-server"));

        assertThat(response.getServerName()).isEqualTo("my-server");
    }

    @Test
    void 정상등록_저장소save호출() {
        given(targetServerRepository.existsByServerName("my-server")).willReturn(false);

        serverRegisterService.registerServer(new RegisterServerRequest("my-server"));

        then(targetServerRepository).should().save(any(TargetServer.class));
    }

    @Test
    void 정상등록_가이드에서버이름과mcpDomain포함() {
        given(targetServerRepository.existsByServerName("my-server")).willReturn(false);

        RegisterServerResponse response = serverRegisterService.registerServer(new RegisterServerRequest("my-server"));

        assertThat(response.getGuide()).contains("my-server");
        assertThat(response.getGuide()).contains("https://mcp.test.com");
    }

    // ===== generateSetupGuide =====

    @Test
    void 가이드_null입력_기본값사용() {
        String guide = serverRegisterService.generateSetupGuide(null, null);

        assertThat(guide).contains("서버이름");
        assertThat(guide).contains("서버 등록 후 발급받은 토큰");
    }

    @Test
    void 가이드_빈문자열_기본값사용() {
        String guide = serverRegisterService.generateSetupGuide("", "");

        assertThat(guide).contains("서버이름");
    }

    @Test
    void 가이드_정상입력_서버이름토큰도메인포함() {
        String guide = serverRegisterService.generateSetupGuide("prod-server", "secret-token");

        assertThat(guide).contains("prod-server");
        assertThat(guide).contains("secret-token");
        assertThat(guide).contains("https://mcp.test.com");
        assertThat(guide).contains("ghcr.io/test/forwarder:latest");
    }
}
