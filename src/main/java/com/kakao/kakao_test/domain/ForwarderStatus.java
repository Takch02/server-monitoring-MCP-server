package com.kakao.kakao_test.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "forwarder_status")
@Getter
@NoArgsConstructor
public class ForwarderStatus extends BaseTimeEntity {
    @Id
    private Long serverId;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false)
    private double startedAt;

    @Column(nullable = false)
    private long reportSequence;

    @Column(nullable = false)
    private Instant receivedAt;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    public ForwarderStatus(Long serverId) {
        this.serverId = serverId;
    }

    public void replace(String runId, double startedAt, long sequence, Instant receivedAt, String json) {
        this.runId = runId;
        this.startedAt = startedAt;
        this.reportSequence = sequence;
        this.receivedAt = receivedAt;
        this.snapshotJson = json;
    }
}
