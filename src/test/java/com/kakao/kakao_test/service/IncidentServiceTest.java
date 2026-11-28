package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.IncidentLinkConfig.Settings;
import com.kakao.kakao_test.domain.Incident;
import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.repository.IncidentRepository;
import com.kakao.kakao_test.repository.ServerLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {
    private static final String INCIDENT_ID = "12345678-1234-1234-1234-123456789abc";
    private static final byte[] TEST_KEY = new byte[32];
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

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
        assertThat(service().getDetail(INCIDENT_ID, null, "Bearer " + "a".repeat(43), null)).isEmpty();
        then(incidents).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 패딩_유무에_관계없이_같은_키로_상세를_조회한다(boolean padded) {
        var service = service();
        var server = TargetServer.builder().serverName("demo").build();
        var log = mock(ServerLog.class);
        given(log.getId()).willReturn(1L);
        given(log.getServer()).willReturn(server);
        var incident = new Incident(INCIDENT_ID, log, null, 1_000L);
        given(incidents.findById(INCIDENT_ID)).willReturn(Optional.of(incident));
        String authorization = authorization(service);
        var encoder = padded ? Base64.getEncoder() : Base64.getEncoder().withoutPadding();

        assertThat(service.getDetail(INCIDENT_ID, encoder.encodeToString(TEST_KEY), authorization, null)).isPresent();
    }

    @Test
    void 잘못된_Base64나_다른_키는_사건을_조회하지_않는다() {
        var service = service();
        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;
        String authorization = "Bearer " + "a".repeat(43);

        assertThat(service.getDetail(INCIDENT_ID, "*".repeat(43), authorization, null)).isEmpty();
        assertThat(service.getDetail(INCIDENT_ID, Base64.getEncoder().encodeToString(wrongKey), authorization, null)).isEmpty();
        then(incidents).shouldHaveNoInteractions();
    }

    private IncidentService service() {
        var settings = new Settings(URI.create("https://server-doctor.example"), new SecretKeySpec(TEST_KEY, "HmacSHA256"));
        return new IncidentService(incidents, logs, settings, CLOCK);
    }

    private String authorization(IncidentService service) {
        String fragment = URI.create(service.linkFor(INCIDENT_ID).orElseThrow()).getFragment();
        return "Bearer " + fragment.substring("token=".length());
    }
}
