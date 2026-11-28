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
import org.springframework.data.domain.PageRequest;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
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

    @Test
    void 대표_로그와_발생_기록에서_JSON_자격증명을_가리고_오류_내용은_보존한다() {
        var service = service();
        var server = TargetServer.builder().serverName("demo").build();
        var log = mock(ServerLog.class);
        var start = LocalDateTime.of(2026, 1, 1, 10, 0);
        var end = start.plusMinutes(5);
        given(log.getId()).willReturn(1L);
        given(log.getServer()).willReturn(server);
        given(log.getCreatedAt()).willReturn(start);
        given(log.getErrorGroupKey()).willReturn("group");
        given(log.getMessage()).willReturn("""
                {"authToken":"token-sample","cookie":"sid=cookie-sample","authorization":"Bearer auth-sample",
                 "proxy-authorization":"Basic proxy-sample","set-cookie":"sid=\\\"cookie-quoted\\\"; Path=/",
                 "message":"외부 API 시간 초과"}
                """);
        var incident = new Incident(INCIDENT_ID, log, end, 1_000L);
        given(incidents.findById(INCIDENT_ID)).willReturn(Optional.of(incident));
        given(logs.findIncidentOccurrences(server, "group", start, end, null, PageRequest.ofSize(6)))
                .willReturn(List.of(log));
        given(logs.findById(1L)).willReturn(Optional.of(log));

        var detail = service.getDetail(INCIDENT_ID, Base64.getEncoder().encodeToString(TEST_KEY),
                authorization(service), null).orElseThrow();

        assertThat(detail.representative().message())
                .contains("[REDACTED]", "외부 API 시간 초과")
                .doesNotContain("token-sample", "cookie-sample", "auth-sample", "proxy-sample", "cookie-quoted", "Path=/");
        assertThat(detail.occurrences()).containsExactly(detail.representative());
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
