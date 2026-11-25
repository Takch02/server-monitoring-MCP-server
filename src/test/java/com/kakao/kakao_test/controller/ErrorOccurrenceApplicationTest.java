package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.repository.ServerLogRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import com.kakao.kakao_test.service.DiscordNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
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
    void 재전송된_오류는_커밋후_알림을_다시_발생시키지_않는다() throws Exception {
        servers.saveAndFlush(TargetServer.builder().serverName("alert-test").mcpToken("alert-token").build());
        String body = """
                [{"eventId":"alert-1","level":"ERROR","ts":1000,"message":"alert"}]
                """;
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/servers/alert-test/ingest/logs").header("X-MCP-TOKEN", "alert-token")
                    .header("X-DISCORD-WEBHOOK-URL", "https://example.invalid/webhook")
                    .contentType("application/json").content(body)).andExpect(status().isOk());
        }
        then(discord).should().sendErrorAlert(anyString(), eq("alert-test"), contains("alert"));
    }
}
