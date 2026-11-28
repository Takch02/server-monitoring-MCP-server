package com.kakao.kakao_test.service;

import com.kakao.kakao_test.config.IncidentLinkConfig.Settings;
import com.kakao.kakao_test.domain.Incident;
import com.kakao.kakao_test.domain.ServerLog;
import com.kakao.kakao_test.dto.IncidentDetailDto;
import com.kakao.kakao_test.dto.IncidentDetailDto.Occurrence;
import com.kakao.kakao_test.repository.IncidentRepository;
import com.kakao.kakao_test.repository.ServerLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IncidentService {
    private static final Duration LINK_LIFETIME = Duration.ofHours(24);
    private static final int PAGE_SIZE = 5;
    private static final int LOG_CHAR_LIMIT = 16384;
    private static final String SECRET_HEADER_NAMES = "(?:authorization|proxy-authorization|cookie|set-cookie)";
    private static final String SECRET_KEYS = "(?:token|(?:auth|access|refresh|ingest|mcp)[_-]?token|x-mcp-token"
            + "|secret|client[_-]?secret|password|db[_-]?password|passwd|api[_-]?key|discord[_-]?webhook[_-]?url|webhook)";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern AUTH_TOKEN = Pattern.compile("(?i)(\\b(?:Bearer|Basic)[ \\t]+)[^\\s,;]+");
    private static final Pattern QUOTED_SECRET = Pattern.compile(
            "(?i)(\\b(?:" + SECRET_HEADER_NAMES + "|" + SECRET_KEYS + ")[\"']?[ \\t]*[:=][ \\t]*)([\"'])"
                    + "(?:\\\\[^\\r\\n]|(?!\\2)[^\\\\\\r\\n])*+\\2");
    private static final Pattern SECRET_HEADER = Pattern.compile(
            "(?i)(\\b" + SECRET_HEADER_NAMES + "[ \\t]*:[ \\t]*)[^\\r\\n]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\\b" + SECRET_KEYS + "[\"']?[ \\t]*[:=][ \\t]*)[^\\s&,;}]+");
    private static final Pattern WEBHOOK = Pattern.compile(
            "(?i)https://(?:[\\w-]+\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/[^\\s\"'<>]+");
    private static final Pattern URL_PASSWORD = Pattern.compile("(://[^:/@\\s]+:)[^@/\\s]+@");

    private final IncidentRepository incidentRepository;
    private final ServerLogRepository serverLogRepository;
    private final Settings settings;
    private final Clock clock;

    @Transactional
    public Optional<String> linkForAlert(String deliveryId, long serverId) {
        if (!settings.enabled()) return Optional.empty();

        long now = clock.millis();
        long expiresAt = now + LINK_LIFETIME.toMillis();
        Incident incident = incidentRepository.findById(deliveryId)
                .orElseGet(() -> captureAlert(deliveryId, serverId, expiresAt));
        if (incident.getExpiresAt() <= now) incident.renewLink(expiresAt);
        return Optional.of(settings.publicOrigin().resolve("/incidents/" + deliveryId) + "#token=" + signature(incident));
    }

    private Incident captureAlert(String deliveryId, long serverId, long expiresAt) {
        ServerLog representative = serverLogRepository
                .findFirstByServer_IdAndAlertDeliveryOrderByCreatedAtAscIdAsc(serverId, deliveryId).orElseThrow();
        var end = serverLogRepository.findLastDeliveryReceivedAt(serverId, deliveryId);
        return incidentRepository.save(new Incident(deliveryId, representative, end, expiresAt, true));
    }

    @Transactional
    public void confirmWindow(String deliveryId, LocalDateTime start, LocalDateTime end) {
        if (settings.enabled()) {
            incidentRepository.findById(deliveryId).ifPresent(incident -> incident.includeFollowupLogs(start, end));
        }
    }

    @Transactional(readOnly = true)
    public Optional<IncidentDetailDto> getDetail(String id, String edgeHeader, String authorization, Long beforeId) {
        if (!settings.enabled() || !matchesEdgeKey(edgeHeader) || !ID.matcher(id).matches()
                || authorization == null || authorization.length() != 50 || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }

        String token = authorization.substring("Bearer ".length());
        return incidentRepository.findById(id)
                .filter(incident -> clock.millis() < incident.getExpiresAt() && matches(token, signature(incident)))
                .map(incident -> detail(incident, beforeId));
    }

    private IncidentDetailDto detail(Incident incident, Long beforeId) {
        String id = incident.getId();
        var server = incident.getServer();
        String groupKey = incident.getErrorGroupKey();
        var start = incident.getWindowStart();
        var end = incident.getWindowEnd();
        boolean deliveryScoped = incident.isDeliveryScoped();
        var limit = PageRequest.ofSize(PAGE_SIZE + 1);
        long count;
        List<ServerLog> logs;
        if (incident.isLiveWindow()) {
            count = serverLogRepository.countLiveWindowOccurrences(server.getId(), id, start, end);
            logs = serverLogRepository.findLiveWindowOccurrences(server.getId(), id, start, end, beforeId, limit);
        } else if (deliveryScoped) {
            count = serverLogRepository.countByServer_IdAndAlertDelivery(server.getId(), id);
            logs = serverLogRepository.findDeliveryOccurrences(server.getId(), id, beforeId, limit);
        } else {
            count = serverLogRepository.countIncidentOccurrences(server, groupKey, start, end);
            logs = serverLogRepository.findIncidentOccurrences(server, groupKey, start, end, beforeId, limit);
        }
        String nextCursor = logs.size() > PAGE_SIZE ? logs.get(PAGE_SIZE - 1).getId().toString() : null;
        var occurrences = logs.stream().limit(PAGE_SIZE).map(this::occurrence).toList();
        Occurrence representative = serverLogRepository.findById(incident.getRepresentativeLogId())
                .map(this::occurrence).orElse(null);
        return new IncidentDetailDto(id, incident.getServer().getServerName(), incident.getErrorGroupKey(),
                incident.getWindowStart(), incident.getWindowEnd(), Instant.ofEpochMilli(incident.getExpiresAt()),
                deliveryScoped, count, representative, occurrences, nextCursor);
    }

    private Occurrence occurrence(ServerLog log) {
        String message = log.getMessage() == null ? "" : log.getMessage();
        message = QUOTED_SECRET.matcher(message).replaceAll("$1[REDACTED]");
        message = SECRET_HEADER.matcher(message).replaceAll("$1[REDACTED]");
        message = AUTH_TOKEN.matcher(message).replaceAll("$1[REDACTED]");
        message = SECRET.matcher(message).replaceAll("$1[REDACTED]");
        message = WEBHOOK.matcher(message).replaceAll("[REDACTED]");
        message = URL_PASSWORD.matcher(message).replaceAll("$1[REDACTED]@");
        var date = log.getOccurredAt();
        boolean truncated = message.length() > LOG_CHAR_LIMIT;
        if (truncated) {
            int end = Character.isHighSurrogate(message.charAt(LOG_CHAR_LIMIT - 1)) ? LOG_CHAR_LIMIT - 1 : LOG_CHAR_LIMIT;
            message = message.substring(0, end);
        }
        return new Occurrence(log.getId().toString(), date == null ? null : date.atZone(ZoneId.systemDefault()).toInstant(),
                message, truncated);
    }

    private String signature(Incident incident) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(settings.signingKey());
            String payload = "incident:v1:" + incident.getId() + ":" + incident.getExpiresAt();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("사건 링크 서명 실패", e);
        }
    }

    private boolean matchesEdgeKey(String supplied) {
        if (supplied == null || (supplied.length() != 43 && supplied.length() != 44)) return false;
        try {
            byte[] decoded = Base64.getDecoder().decode(supplied);
            return MessageDigest.isEqual(settings.signingKey().getEncoded(), decoded);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean matches(String supplied, String expected) {
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
