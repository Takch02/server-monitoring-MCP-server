package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.IncidentLinkConfig.Settings;
import com.kakao.kakao_test.repository.IncidentRepository;
import com.kakao.kakao_test.repository.ServerLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {
    @Mock IncidentRepository incidents;
    @Mock ServerLogRepository logs;

    @Test
    void 공개주소_미설정이면_사건을_저장하거나_링크를_발급하지_않는다() {
        var service = new IncidentService(incidents, logs, new Settings(null, null), Clock.systemUTC());

        service.captureFirstAlert("delivery", 1L);

        assertThat(service.linkFor("delivery")).isEmpty();
        then(logs).shouldHaveNoInteractions();
        then(incidents).shouldHaveNoInteractions();
    }

    @Test
    void Caddy를_거치지_않은_요청은_사건을_조회하지_않는다() {
        var settings = new Settings(URI.create("https://server-doctor.example"),
                new SecretKeySpec(new byte[32], "HmacSHA256"));
        var service = new IncidentService(incidents, logs, settings, Clock.systemUTC());

        assertThat(service.getDetail("12345678-1234-1234-1234-123456789abc", null,
                "Bearer " + "a".repeat(43), 0)).isEmpty();
        then(incidents).shouldHaveNoInteractions();
    }
}
