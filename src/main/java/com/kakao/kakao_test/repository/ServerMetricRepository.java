package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServerMetricRepository extends JpaRepository<ServerMetric, Integer> {

    /**
     * 가장 최근의 Metrics 를 가져옴
     */
    Optional<ServerMetric> findTopByServerOrderByCapturedAtDesc(TargetServer server);

    /**
     * 최근 50개의 Metrics 를 가져옴
     */
    List<ServerMetric> findTop50ByServerOrderByCapturedAtDesc(TargetServer server);
}
