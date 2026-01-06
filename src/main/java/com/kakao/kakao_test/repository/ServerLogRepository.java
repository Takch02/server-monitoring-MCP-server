package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.domain.TargetServer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerLogRepository extends JpaRepository<ServerLog, Long> {
    // 특정 서버의 최신 로그 100개 가져오기
    List<ServerLog> findTop100ByServerOrderByOccurredAtDesc(TargetServer server);
    
    // 특정 서버의 에러 로그만 최신순 조회
    List<ServerLog> findByServerAndLevelOrderByOccurredAtDesc(TargetServer server, String level, Pageable pageable);
}