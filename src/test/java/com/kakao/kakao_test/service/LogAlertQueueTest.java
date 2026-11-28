package com.kakao.kakao_test.service;

import com.kakao.kakao_test.repository.LogAlertDeliveryRepository;
import com.kakao.kakao_test.repository.LogAlertRepository;
import com.kakao.kakao_test.repository.ServerLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LogAlertQueueTest {
    @Mock LogAlertRepository channels;
    @Mock LogAlertDeliveryRepository deliveries;
    @Mock ServerLogRepository logs;
    @Mock Clock clock;
    @Mock IncidentService incidents;
    @InjectMocks LogAlertQueue queue;

    @Test
    void 상세링크가_없는_발송은_후속_오류를_알림에_포함할_창을_열지_않는다() {
        var claim = new LogAlertQueue.Claim(1L, "delivery", "lease", "ciphertext", "message", false);
        given(clock.millis()).willReturn(1_000L);
        given(channels.releaseAfterSuccess(1L, "lease", 1_000L, 301_000L)).willReturn(1);

        queue.complete(claim);

        then(deliveries).should().markSent("delivery", 1_000L);
        then(channels).should().releaseAfterSuccess(1L, "lease", 1_000L, 301_000L);
        then(channels).shouldHaveNoMoreInteractions();
        then(incidents).shouldHaveNoInteractions();
    }

    @Test
    void 상세링크가_있는_발송에_성공하면_수신_기준의_5분_창을_연다() {
        var claim = new LogAlertQueue.Claim(1L, "delivery", "lease", "ciphertext", "message", true);
        var start = LocalDateTime.of(2026, 11, 28, 10, 0);
        given(clock.millis()).willReturn(1_000L);
        given(channels.currentLogReceiptTime()).willReturn(start);
        given(channels.releaseAfterSuccess(1L, "lease", 1_000L, 301_000L)).willReturn(1);

        queue.complete(claim);

        then(deliveries).should().markSent("delivery", 1_000L);
        then(incidents).should().confirmWindow("delivery", start, start.plusMinutes(5));
    }
}
