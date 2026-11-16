package com.kakao.kakao_test.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ForwarderStatusIngestDto(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") String runId,
        @NotNull @Positive Double startedAt,
        @NotNull @Positive Long sequence,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._+\\-]+") String version,
        @NotNull @Positive Double updatedAt,
        @NotNull @Size(min = 4, max = 4) Map<String, @NotNull @Valid StreamStatus> streams,
        @NotNull @PositiveOrZero Integer logQueueSize,
        @NotNull @Positive Integer logQueueCapacity
) {
    private static final Set<String> STREAM_NAMES = Set.of("logs", "log_sender", "metrics", "health");
    private static final String REASON = "authentication_failed|configuration_error|timeout|connection_error|request_error|http_[1-5][0-9]{2}|queue_full|invalid_metric|missing_or_invalid_metric|log_file_unavailable|health_not_configured";

    @JsonIgnore
    @AssertTrue(message = "수집기 구성, 시각 또는 큐 크기가 유효하지 않습니다.")
    public boolean isConsistent() {
        if (startedAt == null || updatedAt == null || !Double.isFinite(startedAt)
                || !Double.isFinite(updatedAt) || startedAt > updatedAt || streams == null
                || !streams.keySet().equals(STREAM_NAMES) || logQueueSize == null
                || logQueueCapacity == null || logQueueSize > logQueueCapacity) {
            return false;
        }
        return streams.values().stream().allMatch(value -> value != null && value.timesValid(updatedAt));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StreamStatus(
            @NotNull Boolean enabled,
            @NotBlank @Pattern(regexp = "starting|running|failed|stopped|disabled") String state,
            Double lastPollAt,
            Double lastCollectionSuccessAt,
            @Pattern(regexp = REASON) String lastCollectionError,
            Double lastSendSuccessAt,
            @Pattern(regexp = REASON) String lastSendError,
            @NotNull @PositiveOrZero Long failedAttempts,
            @NotNull @PositiveOrZero Long collectionFailures,
            @NotNull @PositiveOrZero Long collectedItems,
            @NotNull @PositiveOrZero Long sentItems,
            @NotNull @PositiveOrZero Long droppedItems,
            Double lastDropAt,
            @Pattern(regexp = REASON) String lastDropReason,
            @Size(max = 64) @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*") String workerError,
            @Positive Double pollStaleSeconds
    ) {
        private boolean timesValid(double observedAt) {
            return (pollStaleSeconds == null || Double.isFinite(pollStaleSeconds))
                    && Stream.of(lastPollAt, lastCollectionSuccessAt, lastSendSuccessAt, lastDropAt)
                    .allMatch(value -> value == null || (Double.isFinite(value) && value > 0 && value <= observedAt));
        }
    }
}
