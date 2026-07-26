package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.ServerMetric;
import com.kakao.kakao_test.domain.TargetServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerMetricRepository extends JpaRepository<ServerMetric, Integer> {

    /**
     * 최근 50개의 Metrics 를 가져옴
     */
    List<ServerMetric> findTop50ByServerOrderByCapturedAtDesc(TargetServer server);
}
