package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.LogAlertDelivery;
import com.kakao.kakao_test.repository.LogAlertDeliveryRepository;
import com.kakao.kakao_test.repository.ServerLogRepository;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import com.kakao.kakao_test.repository.LogAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LogAlertQueue {
    private static final Duration ALERT_WINDOW = Duration.ofMinutes(5);
    private static final long LEASE = Duration.ofMinutes(6).toMillis();
    private static final long RETRY = ALERT_WINDOW.toMillis();
    private static final Pattern EXCEPTION = Pattern.compile("\\b([A-Za-z_$][\\w$]*(?:Exception|Error))\\b");
    private final LogAlertRepository logAlertRepository;
    private final LogAlertDeliveryRepository logAlertDeliveryRepository;
    private final ServerLogRepository serverLogRepository;
    private final Clock clock;
    private final IncidentService incidentService;

    public record Claim(long serverId, String deliveryId, String leaseToken, String encryptedWebhook, String message,
                        boolean hasDetailLink) {}
    public List<Long> readyServers() {
        return logAlertRepository.readyServers(clock.millis(), PageRequest.ofSize(100));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(long serverId) {
        // 워커끼리만 같은 서버의 발송 상태를 선점한다. HTTP 호출 전 이 트랜잭션이 끝난다.
        // 비관락을 사용하여 같은 에러를 여러개 보내지 않도록 한다.
        var state = logAlertRepository.lockChannel(serverId);
        if (state.getAvailableAt() > clock.millis()) return null;
        String delivery = state.getActiveDelivery();
        if (delivery == null) {
            // 이전 링크에 이미 포함된 오류를 그 링크의 발송에 배정한다.
            serverLogRepository.assignCoveredPending(serverId);
            delivery = UUID.randomUUID().toString();
            int assigned = serverLogRepository.assignAllPending(delivery, serverId);
            if (assigned == 0) return null;
            logAlertDeliveryRepository.save(new LogAlertDelivery(delivery, serverId));
        }
        String message = summary(serverId, state.getServer().getServerName(), delivery);
        var link = incidentService.linkForAlert(delivery, serverId);
        message += link.map(url -> "\n[자세히 보기](" + url + ")").orElse("");
        String token = UUID.randomUUID().toString();
        state.reserve(delivery, token, clock.millis() + LEASE);
        return new Claim(serverId, delivery, token, state.getEncryptedWebhook(), message, link.isPresent());
    }

    private String summary(long serverId, String name, String delivery) {
        StringBuilder text = new StringBuilder("[").append(name).append("] 오류 알림");
        // 건수는 전체를 집계하고 메시지에는 상위 그룹과 예외명만 담는다.
        var groups = serverLogRepository.topAlertGroups(serverId, delivery, PageRequest.ofSize(8));
        text.append(" · 총 ").append(serverLogRepository.countByServer_IdAndAlertDelivery(serverId, delivery)).append("건");
        for (var group : groups) {
            var match = EXCEPTION.matcher(group.getSample() == null ? "" : group.getSample());
            String label = match.find() ? match.group(1) : "애플리케이션 오류";
            text.append("\n").append(label, 0, Math.min(80, label.length())).append(" (")
                    .append(group.getGroupKey(), 0, 8).append(") ").append(group.getOccurrences()).append("건");
        }
        if (groups.size() == 8) text.append("\n최대 8개 그룹 표시");
        return text.toString();
    }

    @Transactional
    public void complete(Claim claim) {
        var start = claim.hasDetailLink() ? logAlertRepository.currentLogReceiptTime() : null;
        long now = clock.millis();
        if (logAlertRepository.releaseAfterSuccess(claim.serverId(), claim.leaseToken(), now,
                now + ALERT_WINDOW.toMillis()) == 1) {
            logAlertDeliveryRepository.markSent(claim.deliveryId(), now);
            if (start != null) incidentService.confirmWindow(claim.deliveryId(), start, start.plus(ALERT_WINDOW));
        }
    }

    @Transactional
    public void retry(Claim claim) {
        logAlertRepository.releaseForRetry(claim.serverId(), claim.leaseToken(), clock.millis() + RETRY);
    }
}
