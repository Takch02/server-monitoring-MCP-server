package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.ServerHealthEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ServerHealthEventRepository extends JpaRepository<ServerHealthEvent, Long> {
    Optional<ServerHealthEvent> findTop1ByServerNameOrderByTsDesc(String serverName);
}
