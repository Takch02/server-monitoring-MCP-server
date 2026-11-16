package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.exception.NotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TargetServerRepository extends JpaRepository<TargetServer, Long> {

    /**
     * 서버를 찾고 예외처리까지 처리.
     */
    default TargetServer getByServerName(String serverName) {
        return findByServerName(serverName)
                .orElseThrow(() -> new NotFoundException("서버를 찾을 수 없습니다: " + serverName));
    }

    Optional<TargetServer> findByServerName(String serverName);

    // 최초 상태 행 생성과 최신 보고 교체를 서버별로 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TargetServer t WHERE t.serverName = :name")
    Optional<TargetServer> findForStatusUpdate(@Param("name") String serverName);


    boolean existsByServerName(String serverName);
}