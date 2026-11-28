package com.kakao.kakao_test.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

public record IncidentDetailDto(String id, String serverName, String errorGroupKey, LocalDateTime windowStart,
                                LocalDateTime windowEnd, Instant expiresAt, long occurrenceCount,
                                Occurrence representative, List<Occurrence> occurrences, String nextCursor) {
    public record Occurrence(String id, Instant occurredAt, String message, boolean truncated) {}
}
