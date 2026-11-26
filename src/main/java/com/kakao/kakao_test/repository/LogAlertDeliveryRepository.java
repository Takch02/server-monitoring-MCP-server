package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.LogAlertDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LogAlertDeliveryRepository extends JpaRepository<LogAlertDelivery, String> {
    @Modifying
    @Query("update LogAlertDelivery d set d.sentAt=:now where d.id=:delivery")
    void markSent(String delivery, long now);
}
