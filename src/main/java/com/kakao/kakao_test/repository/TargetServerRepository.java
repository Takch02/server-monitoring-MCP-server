package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.exception.NotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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


    /**
     * 로그 수신 후 최근 수신 시간을 변경함.
     * JPA Dirty check 는 느리므로 직접 쿼리를 날림.
     * 로그 수신과 같은 트렌젝션으로 잡을 경우 데드락이 발생했음. (s-lock -> x-lock 으로 promotion 하며 발생)
     * 그래서 트렌젝션을 따로 뺌.
     */
    @Modifying(clearAutomatically = true) // 쿼리 실행 후 영속성 컨텍스트 초기화
    @Query("UPDATE TargetServer t SET t.heartBeat = CURRENT_TIMESTAMP WHERE t.id = :id")
    void updateHeartbeatNow(@Param("id") Long id);

    boolean existsByServerName(String serverName);
}