package com.kakao.kakao_test.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "alerts.worker-enabled", matchIfMissing = true)
public class LogAlertWorker {
    private final LogAlertQueue queue;
    private final LogAlertDestination destination;
    private final DiscordNotificationService discord;

    /**
     * 기본 5초마다 발송 가능한 알림을 확인한다. 서버별 최소 발송 간격은 5분이다.
     */
    @Scheduled(fixedDelayString = "${alerts.poll-ms:5000}")
    public void dispatch() {
        for (long serverId : queue.readyServers()) {
            dispatchServer(serverId);
        }
    }

    @Async
    @TransactionalEventListener
    public void onChannelConfigured(LogAlertDestination.ChannelConfigured event) {
        dispatchServer(event.serverId());
    }

    private void dispatchServer(long serverId) {
        try {
            send(serverId);
        } catch (RuntimeException e) {
            // HTTP 예외 메시지에 웹훅 자격 증명이 포함될 수 있다.
            log.warn("로그 알림 처리 실패: serverId={}, type={}", serverId, e.getClass().getSimpleName());
        }
    }

    void send(long serverId) {
        var claim = queue.claim(serverId);
        if (claim == null) return;
        try {
            discord.sendLogDelivery(destination.decode(serverId, claim.encryptedWebhook()), claim.message());
        } catch (RuntimeException e) {
            queue.retry(claim);
            throw e;
        }
        queue.complete(claim);
    }
}
