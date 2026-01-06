package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.exception.NotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TargetServerRepository extends JpaRepository<TargetServer, Long> {

    Optional<TargetServer> findByServerName(String serverName);

    default TargetServer getByServerName(String serverName) {
        return findByServerName(serverName)
                .orElseThrow(() -> new NotFoundException("서버를 찾을 수 없습니다: " + serverName));
    }



    Optional<TargetServer> findByServerNameAndMcpToken(String serverName, String mcpToken);

    Optional<TargetServer> findByServerNameAndServerUrl(String serverName, String serverUrl);


}