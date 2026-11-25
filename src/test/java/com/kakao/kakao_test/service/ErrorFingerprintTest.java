package com.kakao.kakao_test.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorFingerprintTest {
    @Test
    void 데모_스택없는_NPE는_노선과_순번이_달라도_같은그룹() {
        String npe = "2026-11-25T11:04:10.272Z ERROR 1 --- [scheduling-1] t.p.logic.scheduler.DemoErrorScheduler   : "
                + "[DEMO] 캐시 미스로 인한 NPE — route=마을버스3 seq=0\n\njava.lang.NullPointerException: null";
        assertThat(ErrorFingerprint.of(npe)).isEqualTo(ErrorFingerprint.of(
                npe.replace("마을버스3", "100").replace("seq=0", "seq=19")));
    }

    @Test
    void 가장안쪽_원인타입과_메서드를_사용하고_줄번호는_제외() {
        String trace = "java.lang.RuntimeException: outer\n\tat app.Bus.run(Bus.java:3)\n"
                + "Caused by: java.net.SocketTimeoutException: timeout\n"
                + "\tat test.projectbus.logic.scheduler.DemoErrorScheduler.simulateApiTimeout(DemoErrorScheduler.java:42)";
        assertThat(ErrorFingerprint.of(trace)).isEqualTo(ErrorFingerprint.of(trace.replace(":42", ":70")))
                .isNotEqualTo(ErrorFingerprint.of(trace.replace("simulateApiTimeout", "refreshCache")))
                .isNotEqualTo(ErrorFingerprint.of(trace.replace("SocketTimeoutException", "IllegalArgumentException")));
    }
}
