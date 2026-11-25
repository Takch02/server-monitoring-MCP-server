package com.kakao.kakao_test.controller;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.mcp.ServerDoctorMcpTools;
import com.kakao.kakao_test.repository.TargetServerRepository;
import com.kakao.kakao_test.repository.ServerMetricRepository;
import com.kakao.kakao_test.repository.ServerHealthEventRepository;
import com.kakao.kakao_test.service.HealthService;
import com.kakao.kakao_test.service.MetricService;
import com.kakao.kakao_test.dto.HealthIngestDto;
import com.kakao.kakao_test.dto.MetricIngestDto;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.transaction.annotation.Propagation;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:forwarder-app;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        // H2 MySQL 모드는 TEXT를 VARCHAR로 보고한다. 스키마 생성은 실제 Flyway SQL을 사용한다.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "openai.api-key=unused-local-test", "mcp.server-url=http://example.invalid",
        "forwarder.image-url=example.invalid/forwarder:test"
})
@AutoConfigureMockMvc
@Transactional
class ForwarderStatusApplicationTest {
    @Autowired MockMvc mvc;
    @Autowired TargetServerRepository servers;
    @Autowired ServerDoctorMcpTools tools;
    @Autowired MetricService metrics;
    @Autowired HealthService health;
    @Autowired ServerMetricRepository metricRepository;
    @Autowired ServerHealthEventRepository healthRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired Clock clock;

    @Test
    void 실제Spring설정에서_상태수신과_MCP조회와_종합진단이_연결된다() throws Exception {
        servers.saveAndFlush(TargetServer.builder().serverName("application-test").mcpToken("local-token").build());
        byte[] report;
        try (var source = getClass().getResourceAsStream("/fixtures/forwarder-status-v1.json")) {
            report = source.readAllBytes();
        }
        mvc.perform(post("/api/servers/application-test/ingest/forwarder-status")
                        .header("X-MCP-TOKEN", "local-token").contentType("application/json").content(report))
                .andExpect(status().isOk());
        assertThat(tools.getForwarderStatus("application-test")).contains("Forwarder: OK");
        assertThat(tools.diagnoseServer("application-test", 24))
                .contains("수집 신뢰성", "Forwarder: OK", "Health: UNKNOWN", "자원 상태: UNKNOWN", "로그 부재만으로");
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 지표와_Health저장은_대상서버행을_갱신하지않는다() {
        TargetServer target = servers.saveAndFlush(TargetServer.builder()
                .serverName("ingest-no-target-update").mcpToken("ingest-token").build());
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long updatesBefore = statistics.getEntityStatistics(TargetServer.class.getName()).getUpdateCount();
        try {
            MetricIngestDto dto = new MetricIngestDto();
            dto.setTs(clock.millis());
            MetricIngestDto.MetricData data = new MetricIngestDto.MetricData();
            data.setCpuUsage(0.1);
            data.setMemoryUsed(100);
            data.setMemoryMax(200);
            dto.setData(data);

            metrics.saveMetric(target.getServerName(), dto, "ingest-token", null);
            health.saveHealth(target.getServerName(),
                    new HealthIngestDto(clock.millis(), "DOWN", 5, 503, null), "ingest-token");

            assertThat(metricRepository.count()).isEqualTo(1);
            assertThat(tools.getHealthStatus(target.getServerName())).contains("Health: DOWN");
            assertThat(statistics.getEntityStatistics(TargetServer.class.getName()).getUpdateCount())
                    .isEqualTo(updatesBefore);
        } finally {
            metricRepository.deleteAll();
            healthRepository.deleteAll();
            servers.delete(target);
        }
    }

}
