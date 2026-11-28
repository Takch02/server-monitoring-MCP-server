package com.kakao.kakao_test.controller;

import com.jayway.jsonpath.JsonPath;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.repository.ServerLogRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import com.kakao.kakao_test.service.DiscordNotificationService;
import com.kakao.kakao_test.service.LogAlertQueue;
import com.kakao.kakao_test.service.LogAlertWorker;
import com.kakao.kakao_test.service.LogAlertDestination;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.time.Clock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "alerts.webhook-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "alerts.worker-enabled=false",
        "incidents.public-url=https://server-doctor.example",
        "incidents.link-key=" + ErrorOccurrenceApplicationTest.INCIDENT_KEY,
        "spring.datasource.url=jdbc:h2:mem:error-occurrences;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        // H2 MySQL 모드는 TEXT를 VARCHAR로 보고한다. 스키마 생성은 실제 Flyway SQL을 사용한다.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "openai.api-key=unused-local-test", "mcp.server-url=http://example.invalid",
        "forwarder.image-url=example.invalid/forwarder:test"
})
@AutoConfigureMockMvc
class ErrorOccurrenceApplicationTest {
    @Autowired ServerLogRepository logs;
    @Autowired TargetServerRepository servers;
    @Autowired MockMvc mvc;
    @MockitoBean DiscordNotificationService discord;
    @MockitoBean Clock clock;
    @Autowired LogAlertQueue queue;
    @Autowired LogAlertDestination destination;
    @Autowired JdbcTemplate jdbc;

    static final String INCIDENT_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    private static final String WEBHOOK = "https://discord.com/api/webhooks/10/test-token";

    private void ingest(String name, String events) throws Exception {
        mvc.perform(post("/api/servers/" + name + "/ingest/logs").header("X-MCP-TOKEN", name + "-token")
                .header("X-DISCORD-WEBHOOK-URL", WEBHOOK)
                .contentType("application/json").content(events)).andExpect(status().isOk());
    }

    private String error(String id, String exception) {
        return "{\"eventId\":\"" + id + "\",\"ts\":1000,\"level\":\"ERROR\",\"message\":\"" + exception + "\"}";
    }


    @Transactional
    @Test
    void 같은시각_두발생과_재전송은_서버별_한그룹_두건() throws Exception {
        var first = servers.saveAndFlush(TargetServer.builder().serverName("first").mcpToken("first-token").build());
        var second = servers.saveAndFlush(TargetServer.builder().serverName("second").mcpToken("second-token").build());
        String body = """
                [{"eventId":"one","level":"ERROR","ts":1000,"message":"cache miss seq=1"},
                 {"eventId":"two","level":"ERROR","ts":1000,"message":"cache miss seq=2"},
                 {"eventId":"info","level":"INFO","ts":1000,"message":"ok"}]
                """;
        for (var server : List.of(first, first, second)) {
            mvc.perform(post("/api/servers/" + server.getServerName() + "/ingest/logs")
                    .header("X-MCP-TOKEN", server.getMcpToken()).contentType("application/json").content(body))
                    .andExpect(status().isOk());
        }
        for (var server : List.of(first, second)) {
            var groups = logs.countErrorGroups(server, LocalDateTime.now().minusDays(1),
                    LocalDateTime.now().plusDays(1));
            assertThat(groups).singleElement().satisfies(group -> assertThat(group.getOccurrences()).isEqualTo(2));
            assertThat(logs.findTop100ByServerOrderByOccurredAtDesc(server)).hasSize(3)
                    .allSatisfy(row -> {
                        assertThat(row.getOccurredAt()).isBefore(row.getCreatedAt());
                        assertThat(row.getMessage()).isNotBlank();
                    });
        }
    }

    @Test
    void 발생시각이_없거나_null인_로그를_포함한_배치는_400을_반환한다() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder()
                .serverName("missing-ts").mcpToken("missing-ts-token").build());
        String valid = error("valid", "java.lang.IllegalStateException");
        for (String invalid : List.of(
                """
                {"eventId":"missing","level":"ERROR","message":"timeout"}
                """,
                """
                {"eventId":"null-ts","ts":null,"level":"ERROR","message":"timeout"}
                """)) {
            mvc.perform(post("/api/servers/missing-ts/ingest/logs")
                            .header("X-MCP-TOKEN", "missing-ts-token")
                            .header("X-DISCORD-WEBHOOK-URL", WEBHOOK)
                            .contentType("application/json").content("[" + valid + "," + invalid + "]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("모든 로그에 발생 시각(ts)이 필요합니다."));
        }
        assertThat(logs.findTop100ByServerOrderByOccurredAtDesc(server)).isEmpty();
    }

    @Test
    void 알림_감사_시각은_목적지_변경과_선점_재시도_발송에_기록된다() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder().serverName("audit").mcpToken("audit-token").build());
        ingest("audit", "[" + error("audit-event", "java.lang.IllegalStateException") + "]");
        LocalDateTime channelCreated = jdbc.queryForObject(
                "SELECT created_at FROM log_alert_channel WHERE server_id=?", LocalDateTime.class, server.getId());
        assertThat(channelCreated).isNotNull();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM log_alert_channel WHERE server_id=?",
                LocalDateTime.class, server.getId())).isNotNull();

        LocalDateTime old = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbc.update("UPDATE log_alert_channel SET updated_at=? WHERE server_id=?", old, server.getId());
        mvc.perform(post("/api/servers/audit/ingest/logs").header("X-MCP-TOKEN", "audit-token")
                        .header("X-DISCORD-WEBHOOK-URL", WEBHOOK + "-changed")
                        .contentType("application/json").content("[" + error("audit-event", "java.lang.IllegalStateException") + "]"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT updated_at FROM log_alert_channel WHERE server_id=?",
                LocalDateTime.class, server.getId())).isAfter(old);

        jdbc.update("UPDATE log_alert_channel SET updated_at=? WHERE server_id=?", old, server.getId());
        given(clock.millis()).willReturn(1000L);
        var claim = queue.claim(server.getId());
        assertThat(jdbc.queryForObject("SELECT updated_at FROM log_alert_channel WHERE server_id=?",
                LocalDateTime.class, server.getId())).isAfter(old);
        LocalDateTime deliveryCreated = jdbc.queryForObject(
                "SELECT created_at FROM log_alert_delivery WHERE id=?", LocalDateTime.class, claim.deliveryId());
        assertThat(deliveryCreated).isNotNull();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM log_alert_delivery WHERE id=?",
                LocalDateTime.class, claim.deliveryId())).isNotNull();

        jdbc.update("UPDATE log_alert_channel SET updated_at=? WHERE server_id=?", old, server.getId());
        queue.retry(claim);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM log_alert_channel WHERE server_id=?",
                LocalDateTime.class, server.getId())).isAfter(old);

        given(clock.millis()).willReturn(301_000L);
        var retry = queue.claim(server.getId());
        jdbc.update("UPDATE log_alert_channel SET updated_at=? WHERE server_id=?", old, server.getId());
        jdbc.update("UPDATE log_alert_delivery SET updated_at=? WHERE id=?", old, retry.deliveryId());
        queue.complete(retry);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM log_alert_channel WHERE server_id=?",
                LocalDateTime.class, server.getId())).isAfter(old);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM log_alert_delivery WHERE id=?",
                LocalDateTime.class, retry.deliveryId())).isAfter(old);
        assertThat(jdbc.queryForObject("SELECT created_at FROM log_alert_channel WHERE server_id=?",
                LocalDateTime.class, server.getId())).isEqualTo(channelCreated);
        assertThat(jdbc.queryForObject("SELECT created_at FROM log_alert_delivery WHERE id=?",
                LocalDateTime.class, retry.deliveryId())).isEqualTo(deliveryCreated);
    }

    @Test
    void 첫알림_링크는_같은서버와_오류그룹의_첫_5분을_24시간_보여준다() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder().serverName("incident").mcpToken("incident-token").build());
        servers.saveAndFlush(TargetServer.builder().serverName("incident-other").mcpToken("incident-other-token").build());
        String detail = "java.lang.IllegalStateException: password=private-value";
        String repeated = IntStream.range(0, 7).mapToObj(index -> error("initial-" + index, detail))
                .collect(Collectors.joining(","));
        ingest("incident-other", "[" + error("other", detail) + "]");
        ingest("incident", "[" + repeated + "," + error("different", "java.lang.NullPointerException") + "]");
        given(clock.millis()).willReturn(1_000L);
        var first = queue.claim(server.getId());
        assertThat(first.message()).contains("총 1건", "[자세히 보기]");
        String link = first.message().substring(first.message().lastIndexOf("](") + 2, first.message().length() - 1);
        URI uri = URI.create(link);
        assertThat(uri.getHost()).isEqualTo("server-doctor.example");
        String path = "/api" + uri.getPath();
        String authorization = "Bearer " + uri.getFragment().substring("token=".length());

        var firstResponse = mvc.perform(get(path).header("X-Incident-Edge", INCIDENT_KEY)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.occurrenceCount").value(7))
                .andExpect(jsonPath("$.representative.message").value("java.lang.IllegalStateException: password=[REDACTED]"))
                .andExpect(jsonPath("$.occurrences.length()").value(5))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String cursor = JsonPath.read(firstResponse.getResponse().getContentAsString(), "$.nextCursor");

        ingest("incident", "[" + error("minute-four", detail) + "," + error("minute-five", detail) + ","
                + error("hour-later", detail) + "," + error("before-first", detail) + "]");
        LocalDateTime start = jdbc.queryForObject("SELECT window_start FROM incident WHERE id=?",
                LocalDateTime.class, first.deliveryId());
        String setReceivedAt = "UPDATE server_log SET created_at=? WHERE event_id=?";
        jdbc.update(setReceivedAt, start.plusMinutes(4), "minute-four");
        jdbc.update(setReceivedAt, start.plusMinutes(5), "minute-five");
        jdbc.update(setReceivedAt, start.plusHours(1), "hour-later");
        jdbc.update(setReceivedAt, start.minusSeconds(1), "before-first");
        Long newLogId = jdbc.queryForObject("SELECT id FROM server_log WHERE event_id='minute-four'", Long.class);

        mvc.perform(get(path).header("X-Incident-Edge", INCIDENT_KEY).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceCount").value(8))
                .andExpect(jsonPath("$.occurrences[0].id").value(newLogId.toString()));

        mvc.perform(get(path).param("before", cursor).header("X-Incident-Edge", INCIDENT_KEY)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceCount").value(8))
                .andExpect(jsonPath("$.occurrences.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        mvc.perform(get(path).header("Authorization", authorization)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("X-Incident-Edge", INCIDENT_KEY)).andExpect(status().isNotFound());
        String invalidAuth = authorization.substring(0, authorization.length() - 1)
                + (authorization.endsWith("a") ? "b" : "a");
        mvc.perform(get(path).header("X-Incident-Edge", INCIDENT_KEY).header("Authorization", invalidAuth))
                .andExpect(status().isNotFound());

        var other = queue.claim(servers.getByServerName("incident-other").getId());
        mvc.perform(get("/api/incidents/" + other.deliveryId()).header("X-Incident-Edge", INCIDENT_KEY)
                        .header("Authorization", authorization))
                .andExpect(status().isNotFound());
        queue.complete(other);

        queue.retry(first);
        given(clock.millis()).willReturn(301_000L);
        var retry = queue.claim(server.getId());
        assertThat(retry.message()).isEqualTo(first.message());
        queue.complete(retry);

        given(clock.millis()).willReturn(3_601_000L);
        mvc.perform(get(path).header("X-Incident-Edge", INCIDENT_KEY).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceCount").value(8))
                .andExpect(jsonPath("$.occurrences[0].id").value(newLogId.toString()));

        given(clock.millis()).willReturn(86_401_000L);
        mvc.perform(get(path).header("X-Incident-Edge", INCIDENT_KEY).header("Authorization", authorization))
                .andExpect(status().isNotFound());
        var summary = queue.claim(server.getId());
        assertThat(summary.message()).contains("[자세히 보기]");
        queue.complete(summary);
    }

    @Test
    void 집계_링크는_그_알림의_모든_오류_종류를_보여주고_재시도에도_유지된다() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder().serverName("incident-summary")
                .mcpToken("incident-summary-token").build());
        String npe = "java.lang.NullPointerException";
        String timeout = "java.net.SocketTimeoutException";
        String batch = IntStream.range(0, 6).mapToObj(index -> error("summary-" + index, index < 4 ? npe : timeout))
                .collect(Collectors.joining(","));
        ingest("incident-summary", "[" + error("summary-first", "java.lang.IllegalStateException") + "," + batch + "]");
        given(clock.millis()).willReturn(1_000L);
        queue.complete(queue.claim(server.getId()));

        given(clock.millis()).willReturn(301_000L);
        var summary = queue.claim(server.getId());
        assertThat(summary.message()).contains("총 6건", "NullPointerException", "SocketTimeoutException", "[자세히 보기]");
        URI link = URI.create(summary.message().substring(summary.message().lastIndexOf("](") + 2,
                summary.message().length() - 1));
        String path = "/api" + link.getPath();
        String authorization = "Bearer " + link.getFragment().substring("token=".length());

        ingest("incident-summary", "[" + error("summary-later", npe) + "]");
        var response = mvc.perform(get(path).header("X-Incident-Edge", INCIDENT_KEY)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryScoped").value(true))
                .andExpect(jsonPath("$.occurrenceCount").value(6))
                .andExpect(jsonPath("$.occurrences.length()").value(5))
                .andExpect(jsonPath("$.occurrences[*].message", hasItems(npe, timeout)))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String cursor = JsonPath.read(response.getResponse().getContentAsString(), "$.nextCursor");
        mvc.perform(get(path).param("before", cursor).header("X-Incident-Edge", INCIDENT_KEY)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences.length()").value(1))
                .andExpect(jsonPath("$.occurrences[0].message").value(npe))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        queue.retry(summary);
        given(clock.millis()).willReturn(601_000L);
        var retry = queue.claim(server.getId());
        assertThat(retry.message()).isEqualTo(summary.message());
        queue.complete(retry);
    }

    @Test
    void 링크_없이_배정된_미발송_집계가_재시도되면_같은_알림에_링크를_만든다() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder().serverName("pending-summary")
                .mcpToken("pending-summary-token").build());
        ingest("pending-summary", "[" + error("pending-summary-first", "java.lang.NullPointerException") + ","
                + error("pending-summary-next", "java.net.SocketTimeoutException") + "]");
        given(clock.millis()).willReturn(1_000L);
        queue.complete(queue.claim(server.getId()));

        given(clock.millis()).willReturn(301_000L);
        var summary = queue.claim(server.getId());
        queue.retry(summary);
        // 이전 버전에서 발송이 실패한 집계는 발송 ID와 배정 로그만 남아 있다.
        assertThat(jdbc.update("DELETE FROM incident WHERE id = ?", summary.deliveryId())).isEqualTo(1);

        given(clock.millis()).willReturn(601_000L);
        var retry = queue.claim(server.getId());

        assertThat(retry.deliveryId()).isEqualTo(summary.deliveryId());
        assertThat(retry.message()).contains("총 1건", "SocketTimeoutException", "[자세히 보기]");
        assertThat(jdbc.queryForObject("SELECT delivery_scoped FROM incident WHERE id = ?", Boolean.class,
                retry.deliveryId())).isTrue();
        queue.complete(retry);
    }

    @Test
    void 첫오류_후_5분마다_미발송분을_반복그룹까지_집계한다() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder().serverName("interval").mcpToken("interval-token").build());
        String groupA = "java.net.SocketTimeoutException";
        String groupB = "java.lang.IllegalStateException";
        String first = error("first", groupA);
        ingest("interval", "[" + first + "," + error("second", groupA) + "," + error("third", groupB) + "]");
        var worker = new LogAlertWorker(queue, destination, discord);
        given(clock.millis()).willReturn(1_000L);
        worker.dispatch();
        then(discord).should().sendLogDelivery(eq(WEBHOOK), contains("총 1건"));
        ingest("interval", "[" + first + "," + error("fourth", groupA) + "]");
        given(clock.millis()).willReturn(300_999L);
        worker.dispatch();
        then(discord).shouldHaveNoMoreInteractions();
        given(clock.millis()).willReturn(301_000L);
        worker.dispatch();
        then(discord).should().sendLogDelivery(eq(WEBHOOK), argThat(message ->
                message.contains("총 3건") && message.contains("SocketTimeoutException")
                        && message.contains("2건") && message.contains("IllegalStateException")));
        ingest("interval", "[" + error("repeat", groupA) + "]");
        given(clock.millis()).willReturn(601_000L);
        worker.dispatch();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM log_alert_delivery WHERE server_id=? AND sent_at IS NOT NULL",
                Long.class, server.getId())).isEqualTo(3);
        assertThat(queue.readyServers()).doesNotContain(server.getId());
    }

    @Test
    void 한서버의_웹훅실패가_다른서버의_발송을_막지않는다() throws Exception {
        var one = servers.saveAndFlush(TargetServer.builder().serverName("left").mcpToken("left-token").build());
        var two = servers.saveAndFlush(TargetServer.builder().serverName("right").mcpToken("right-token").build());
        String payload = "[" + error("same", "java.lang.IllegalStateException") + "]";
        ingest("left", payload);
        ingest("right", payload);
        var worker = new LogAlertWorker(queue, destination, discord);
        willThrow(new IllegalStateException("receiver unavailable")).willDoNothing().given(discord)
                .sendLogDelivery(eq(WEBHOOK), startsWith("[left]"));
        given(clock.millis()).willReturn(100L);
        worker.dispatch();
        assertThat(sentCount(one)).isZero();
        assertThat(sentCount(two)).isEqualTo(1);
        assertThat(queue.claim(one.getId())).isNull();
        given(clock.millis()).willReturn(300_100L);
        worker.dispatch();
        assertThat(sentCount(one)).isEqualTo(1);
        assertThat(sentCount(two)).isEqualTo(1);
    }

    private long sentCount(TargetServer server) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM log_alert_delivery WHERE server_id=? AND sent_at IS NOT NULL",
                Long.class, server.getId());
    }

    @Test
    void 발송실패와_워커재시작은_같은_발송건을_재시도한다() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder().serverName("retry").mcpToken("retry-token").build());
        ingest("retry", "[" + error("event", "java.lang.IllegalStateException") + "]");
        given(clock.millis()).willReturn(1_000L);
        var first = queue.claim(server.getId());
        assertThat(first.encryptedWebhook()).doesNotContain(WEBHOOK);
        assertThat(queue.claim(server.getId())).isNull();
        given(clock.millis()).willReturn(361_000L);
        var recovered = queue.claim(server.getId());
        assertThat(recovered.deliveryId()).isEqualTo(first.deliveryId());
        queue.complete(first); // 만료된 워커는 새 워커의 상태를 바꿀 수 없다.
        queue.retry(recovered);
        assertThat(queue.claim(server.getId())).isNull();
        given(clock.millis()).willReturn(661_000L);
        var finalClaim = queue.claim(server.getId());
        assertThat(finalClaim.deliveryId()).isEqualTo(first.deliveryId());
        queue.complete(finalClaim);
        assertThat(queue.readyServers()).doesNotContain(server.getId());
    }

    @Test
    void 동일한_오류_배치가_동시에_도착해도_한번만_알림() throws Exception {
        var server = servers.saveAndFlush(TargetServer.builder().serverName("parallel").mcpToken("test-token").build());
        var start = new CyclicBarrier(2);
        Callable<Void> send = () -> {
            start.await(5, TimeUnit.SECONDS);
            mvc.perform(post("/api/servers/parallel/ingest/logs").header("X-MCP-TOKEN", "test-token")
                    .header("X-DISCORD-WEBHOOK-URL", "https://discord.com/api/webhooks/10/test-token")
                    .contentType("application/json").content("""
                            [{"eventId":"e1","ts":1000,"level":"error","message":"timeout"}]
                            """)).andExpect(status().isOk());
            return null;
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (var result : pool.invokeAll(List.of(send, send), 15, TimeUnit.SECONDS)) {
                result.get();
            }
        }
        assertThat(logs.findTop100ByServerOrderByOccurredAtDesc(server)).hasSize(1);
        var claim = queue.claim(server.getId());
        assertThat(claim.message()).contains("총 1건");
        assertThat(queue.claim(server.getId())).isNull();
        queue.complete(claim);
        then(discord).shouldHaveNoMoreInteractions();
    }

}
