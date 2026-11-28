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
import java.time.ZoneId;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IncidentService {
    private static final Duration LINK_LIFETIME = Duration.ofHours(24);
    private static final int INCIDENT_WINDOW_MINUTES = 5;
    private static final int PAGE_SIZE = 5;
    private static final int LOG_CHAR_LIMIT = 16384;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern AUTH_TOKEN = Pattern.compile("(?i)(\\b(?:Bearer|Basic)[ \\t]+)[^\\s,;]+");
    private static final Pattern SECRET_HEADER = Pattern.compile(
            "(?im)(\\b(?:authorization|proxy-authorization|cookie|set-cookie)[ \\t]*:[ \\t]*)[^\\r\\n]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\\b(?:token|(?:access|refresh|ingest|mcp)[_-]?token|x-mcp-token|secret|client[_-]?secret"
                    + "|password|db[_-]?password|passwd|api[_-]?key|discord[_-]?webhook[_-]?url|webhook)"
                    + "[\"']?[ \\t]*[:=][ \\t]*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s&,;}]+)");
    private static final Pattern WEBHOOK = Pattern.compile(
            "(?i)https://(?:[\\w-]+\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/[^\\s\"'<>]+");
    private static final Pattern URL_PASSWORD = Pattern.compile("(://[^:/@\\s]+:)[^@/\\s]+@");

    private final IncidentRepository incidentRepository;
    private final ServerLogRepository serverLogRepository;
    private final Settings settings;
    private final Clock clock;

    @Transactional
    public void captureFirstAlert(String deliveryId, long serverId) {
        if (!settings.enabled()) return;

        ServerLog representative = serverLogRepository.findFirstByServer_IdAndAlertDeliveryOrderById(serverId, deliveryId)
                .orElseThrow();
        Incident incident = new Incident(deliveryId, representative,
                representative.getCreatedAt().plusMinutes(INCIDENT_WINDOW_MINUTES),
                clock.millis() + LINK_LIFETIME.toMillis());
        incidentRepository.save(incident);
    }

    @Transactional(readOnly = true)
    public Optional<String> linkFor(String incidentId) {
        if (!settings.enabled()) return Optional.empty();

        return incidentRepository.findById(incidentId)
                .filter(incident -> clock.millis() < incident.getExpiresAt())
                .map(incident -> settings.publicOrigin().resolve("/incidents/" + incident.getId())
                        + "#token=" + signature(incident));
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
        long count = serverLogRepository.countIncidentOccurrences(server, groupKey, start, end);
        var logs = serverLogRepository.findIncidentOccurrences(server, groupKey, start, end, beforeId,
                PageRequest.ofSize(PAGE_SIZE + 1));
        String nextCursor = logs.size() > PAGE_SIZE ? logs.get(PAGE_SIZE - 1).getId().toString() : null;
        var occurrences = logs.stream().limit(PAGE_SIZE).map(this::occurrence).toList();
        Occurrence representative = serverLogRepository.findById(incident.getRepresentativeLogId())
                .map(this::occurrence).orElse(null);
        return new IncidentDetailDto(id, incident.getServer().getServerName(), incident.getErrorGroupKey(),
                incident.getWindowStart(), incident.getWindowEnd(), Instant.ofEpochMilli(incident.getExpiresAt()),
                count, representative, occurrences, nextCursor);
    }

    private Occurrence occurrence(ServerLog log) {
        String message = log.getMessage() == null ? "" : log.getMessage();
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
