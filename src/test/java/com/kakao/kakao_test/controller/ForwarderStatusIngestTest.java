package com.kakao.kakao_test.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kakao.kakao_test.config.DiagnosticProperties;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.exception.GlobalExceptionHandler;
import com.kakao.kakao_test.repository.ForwarderStatusRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import com.kakao.kakao_test.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({ForwarderStatusService.class, ForwarderStatusIngestTest.TestConfig.class})
class ForwarderStatusIngestTest {
    @TestConfiguration
    static class TestConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC); }
        @Bean DiagnosticProperties diagnostics() { return new DiagnosticProperties(); }
    }

    @Autowired ForwarderStatusService service;
    @Autowired ForwarderStatusRepository statuses;
    @Autowired TargetServerRepository servers;
    @Autowired ObjectMapper mapper;
    private MockMvc mvc;
    private ObjectNode report;
    private TargetServer server;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new IngestController(mock(MetricService.class), mock(LogService.class),
                        mock(HealthService.class), service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        server = servers.saveAndFlush(TargetServer.builder().serverName("test").mcpToken("token").build());
        try (var source = getClass().getResourceAsStream("/fixtures/forwarder-status-v1.json")) {
            report = (ObjectNode) mapper.readTree(source);
        }
    }

    @Test
    void HTTP수신부터_DB최신행교체와_진단조회까지_연결된다() throws Exception {
        submit("token", report).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        assertThat(statuses.count()).isEqualTo(1);
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: OK", "로그 큐: 0/300");
        report.put("sequence", 2);
        ((ObjectNode) report.path("streams").path("metrics")).put("last_collection_error", "missing_or_invalid_metric");
        submit("token", report).andExpect(status().isOk());
        statuses.flush();
        assertThat(statuses.count()).isEqualTo(1);
        assertThat(statuses.findById(server.getId()).orElseThrow().getReportSequence()).isEqualTo(2);
        assertThat(service.getStatusForMcp("test")).contains("Forwarder: DEGRADED", "missing_or_invalid_metric");
    }

    @Test
    void 인증실패는_401이며_DB에_상태를_생성하지않는다() throws Exception {
        submit("wrong-token", report).andExpect(status().isUnauthorized());
        assertThat(statuses.count()).isZero();
        TargetServer unchanged = servers.findById(server.getId()).orElseThrow();
        assertThat(unchanged.getServerName()).isEqualTo("test");
        assertThat(unchanged.getMcpToken()).isEqualTo("token");
    }

    @Test
    void 누락된토큰은_상태를_저장하지않는다() throws Exception {
        mvc.perform(post("/api/servers/test/ingest/forwarder-status")
                .contentType("application/json").content(mapper.writeValueAsBytes(report)))
                .andExpect(status().is4xxClientError());
        assertThat(statuses.count()).isZero();
    }

    @Test
    void 누락되거나_지원하지않는본문형식은_415이며_상태를_저장하지않는다() throws Exception {
        for (String contentType : new String[]{null, "application/octet-stream", "text/plain"}) {
            var request = post("/api/servers/test/ingest/forwarder-status")
                    .header("X-MCP-TOKEN", "token").content(mapper.writeValueAsBytes(report));
            if (contentType != null) request.contentType(contentType);
            mvc.perform(request).andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
        }
        assertThat(statuses.count()).isZero();
    }

    @Test
    void 잘못된JSON은_400이며_본문원문을_응답하지않는다() throws Exception {
        mvc.perform(post("/api/servers/test/ingest/forwarder-status").header("X-MCP-TOKEN", "token")
                        .contentType("application/json").content("{ secret-test-value"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-test-value"))));
        assertThat(statuses.count()).isZero();
    }

    @Test
    void 잘못된구조와_음수카운터와_알수없는오류본문은_400이다() throws Exception {
        for (String kind : new String[]{"missing", "unknown_stream", "negative", "reason", "future_loop", "queue", "version", "schema"}) {
            ObjectNode invalid = report.deepCopy();
            ObjectNode streams = (ObjectNode) invalid.path("streams");
            ObjectNode logs = (ObjectNode) streams.path("logs");
            switch (kind) {
                case "missing" -> invalid.remove("run_id");
                case "unknown_stream" -> streams.set("extra", logs.deepCopy());
                case "negative" -> logs.put("sent_items", -1);
                case "reason" -> logs.put("last_send_error", "HTTP body with secret token");
                case "future_loop" -> logs.put("last_poll_at", report.path("updated_at").asDouble() + 1);
                case "queue" -> invalid.put("log_queue_size", 301);
                case "version" -> invalid.put("version", "a".repeat(65));
                case "schema" -> invalid.put("schema_version", 2);
            }
            submit("token", invalid).andExpect(status().isBadRequest());
        }
        assertThat(statuses.count()).isZero();
    }

    @Test
    void 순번없는보고와_비어있는수집기는_정상으로_저장하지않는다() throws Exception {
        report.remove("sequence");
        submit("token", report).andExpect(status().isBadRequest());
        report.put("sequence", 1);
        report.putObject("streams");
        submit("token", report).andExpect(status().isBadRequest());
        assertThat(statuses.count()).isZero();
    }

    @Test
    void 알수없는부가필드는_상태본문에_저장하지않는다() throws Exception {
        report.put("mcp_token", "secret-must-not-be-stored");
        ((ObjectNode) report.path("streams").path("logs")).put("raw_message", "secret-must-not-be-stored");
        submit("token", report).andExpect(status().isOk());
        assertThat(statuses.findById(server.getId()).orElseThrow().getSnapshotJson())
                .doesNotContain("secret-must-not-be-stored", "mcp_token", "raw_message");
    }

    @Test
    void 중복보고는_200으로_수락하지않았음을_알리고_행을_추가하지않는다() throws Exception {
        submit("token", report).andExpect(status().isOk());
        submit("token", report).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(false));
        assertThat(statuses.count()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동시최초보고도_한행만_생성하고_가장큰순번을_보존한다() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int sequence : new int[]{4, 1, 3, 2}) {
                ObjectNode copy = report.deepCopy().put("sequence", sequence);
                var dto = mapper.treeToValue(copy, com.kakao.kakao_test.dto.ForwarderStatusIngestDto.class);
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.ingest("test", "token", dto);
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertThat(statuses.count()).isEqualTo(1);
            assertThat(statuses.findById(server.getId()).orElseThrow().getReportSequence()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanupCommittedRows() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            statuses.deleteAll();
            servers.deleteAll();
        }
    }

    private org.springframework.test.web.servlet.ResultActions submit(String token, ObjectNode payload) throws Exception {
        return mvc.perform(post("/api/servers/test/ingest/forwarder-status").header("X-MCP-TOKEN", token)
                .contentType("application/json").content(mapper.writeValueAsBytes(payload)));
    }
}
