package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.repository.ServerLogRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import com.kakao.kakao_test.service.DiscordNotificationService;
import com.kakao.kakao_test.service.LogAlertQueue;
import com.kakao.kakao_test.service.LogAlertWorker;
import com.kakao.kakao_test.service.LogAlertDestination;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "alerts.webhook-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "alerts.worker-enabled=false",
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
