package com.kakao.kakao_test.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DatabaseKeepAliveScheduler {

    private final JdbcTemplate jdbcTemplate;

    // JdbcTemplate을 주입받아 데이터베이스와 통신할 준비를 합니다.
    public DatabaseKeepAliveScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // fixedRate = 3600000: 1시간(3,600,000밀리초)마다 한 번씩 무조건 실행
    @Scheduled(fixedRate = 3600000)
    public void pingDatabase() {
        try {
            // DB에 부하를 주지 않는 가장 가벼운 생존 신고 쿼리를 날립니다.
            jdbcTemplate.execute("SELECT 1");
            log.info("✅ [생존 신고] Aiven DB 연결 유지용 핑(Ping) 전송 완료");
        } catch (Exception e) {
            log.error("❌ [생존 신고 실패] DB 연결에 문제가 발생했습니다: {}", e.getMessage());
        }
    }
}