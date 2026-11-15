package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ServerMetricRepositoryTest {
    @Autowired TestEntityManager entityManager;
    @Autowired ServerMetricRepository repository;

    @Test
    void 관측기간_경계는_포함하고_과거_미래_다른서버는_제외한다() {
        TargetServer target = entityManager.persist(TargetServer.builder().serverName("target").mcpToken("target-token").build());
        TargetServer other = entityManager.persist(TargetServer.builder().serverName("other").mcpToken("other-token").build());
        LocalDateTime to = LocalDateTime.of(2026, 11, 15, 12, 0);
        LocalDateTime from = to.minusMinutes(10);
        ServerMetric atStart = sample(target, from);
        ServerMetric atEnd = sample(target, to);
        sample(target, from.minusSeconds(1));
        sample(target, to.plusSeconds(1));
        sample(other, to);
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findByServerAndCapturedAtBetweenOrderByCapturedAtDesc(target, from, to))
                .extracting(ServerMetric::getId).containsExactly(atEnd.getId(), atStart.getId());
    }

    private ServerMetric sample(TargetServer server, LocalDateTime at) {
        return entityManager.persist(ServerMetric.builder().server(server).cpuUsage(10.0)
                .memoryUsedMb(100.0).memoryMaxMb(1000.0).capturedAt(at).build());
    }
}
