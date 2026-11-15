package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ServerMetricRepository extends JpaRepository<ServerMetric, Integer> {

    List<ServerMetric> findByServerAndCapturedAtBetweenOrderByCapturedAtDesc(
            TargetServer server, LocalDateTime from, LocalDateTime to);

    Optional<ServerMetric> findTopByServerOrderByCapturedAtDesc(TargetServer server);
}
