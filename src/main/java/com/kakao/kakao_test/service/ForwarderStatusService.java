package com.kakao.kakao_test.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kakao.kakao_test.config.DiagnosticProperties;
import com.kakao.kakao_test.domain.ForwarderStatus;
import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.ForwarderStatusIngestDto;
import com.kakao.kakao_test.dto.ForwarderStatusIngestDto.StreamStatus;
import com.kakao.kakao_test.exception.NotFoundException;
import com.kakao.kakao_test.exception.UnauthorizedException;
import com.kakao.kakao_test.repository.ForwarderStatusRepository;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ForwarderStatusService {
    private static final List<String> STREAM_NAMES = List.of("logs", "log_sender", "metrics", "health");
    private final TargetServerRepository targetServerRepository;
    private final ForwarderStatusRepository statusRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DiagnosticProperties properties;

    @Transactional
    public boolean ingest(String serverName, String token, ForwarderStatusIngestDto report) {
        // 동일 서버의 동시 보고가 최초 행을 중복 생성하거나 역순으로 교체하지 않도록 잠근다.
        TargetServer server = targetServerRepository.findForStatusUpdate(serverName)
                .orElseThrow(() -> new NotFoundException("서버를 찾을 수 없습니다."));
        if (token == null || !token.equals(server.getMcpToken())) {
            throw new UnauthorizedException("서버 인증에 실패했습니다.");
        }
        ForwarderStatus latest = statusRepository.findById(server.getId())
                .orElseGet(() -> new ForwarderStatus(server.getId()));
        if (latest.getRunId() != null) {
            boolean sameRun = latest.getRunId().equals(report.runId());
            if (sameRun ? report.startedAt() != latest.getStartedAt() || report.sequence() <= latest.getReportSequence()
                    : report.startedAt() <= latest.getStartedAt()) {
                return false;
            }
        }
        latest.replace(report.runId(), report.startedAt(), report.sequence(), clock.instant(), serialize(report));
        statusRepository.save(latest);
        return true;
    }

    @Transactional(readOnly = true)
    public String getStatusForMcp(String serverName) {
        TargetServer server = targetServerRepository.getByServerName(serverName);
        return statusRepository.findById(server.getId()).map(this::format)
                .orElse("Forwarder: UNKNOWN — 상태 보고가 없습니다. 이전 버전 또는 상태 전송 설정을 확인하십시오. 서버 정상 여부를 판정할 수 없습니다.");
    }

    private String format(ForwarderStatus latest) {
        Duration age = Duration.between(latest.getReceivedAt(), clock.instant());
        if (age.isNegative() || age.compareTo(Duration.ofSeconds(properties.getForwarderStaleSeconds())) > 0) {
            return "Forwarder: UNKNOWN — 상태 보고가 끊겼거나 수신 시각이 유효하지 않습니다.\n마지막 서버 수신: "
                    + latest.getReceivedAt() + "\n앱 장애로 단정할 수 없습니다. Forwarder·호스트·네트워크를 확인하십시오.";
        }
        ForwarderStatusIngestDto report = deserialize(latest.getSnapshotJson());
        List<String> problems = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        boolean anyEnabled = false;
        boolean anyDropped = false;
        for (String name : STREAM_NAMES) {
            StreamStatus stream = report.streams().get(name);
            if (!stream.enabled()) {
                detail.append("- ").append(name).append(": 비활성 — 이 경로의 관측 근거 없음\n");
                continue;
            }
            anyEnabled = true;
            anyDropped |= stream.droppedItems() > 0;
            double pollLimit = Math.max(properties.getForwarderStaleSeconds(),
                    stream.pollStaleSeconds() == null ? 0 : stream.pollStaleSeconds());
            boolean stale = stream.lastPollAt() == null || report.updatedAt() - stream.lastPollAt() > pollLimit;
            boolean recentDrop = stream.lastDropAt() != null
                    && report.updatedAt() - stream.lastDropAt() <= properties.getForwarderStaleSeconds();
            boolean failed = !"running".equals(stream.state()) || stale || recentDrop
                    || stream.lastCollectionError() != null || stream.lastSendError() != null || stream.workerError() != null;
            if (failed) {
                problems.add(name);
            }
            detail.append("- ").append(name).append(": ").append(failed ? "확인 필요" : "동작 중")
                    .append(" (state=").append(stream.state()).append(", 수집=").append(stream.collectedItems())
                    .append(", HTTP 수락=").append(stream.sentItems()).append(", 누적 폐기=").append(stream.droppedItems())
                    .append(", 실패 시도=").append(stream.failedAttempts()).append(")");
            if (stale) detail.append(" / 루프 활동 지연");
            if (stream.lastCollectionError() != null) detail.append(" / 수집 오류: ").append(stream.lastCollectionError());
            if (stream.lastSendError() != null) detail.append(" / 전송 오류: ").append(stream.lastSendError());
            if (stream.workerError() != null) detail.append(" / 스레드 오류: ").append(stream.workerError());
            if (recentDrop) detail.append(" / 최근 폐기: ").append(stream.lastDropReason());
            detail.append("\n");
        }
        if (!anyEnabled) problems.add("활성 수집기 없음");
        if (!report.streams().get("logs").enabled().equals(report.streams().get("log_sender").enabled())) {
            problems.add("로그 수집·전송 설정 불일치");
        }
        String state = problems.isEmpty() ? "OK" : "DEGRADED";
        String warning = problems.isEmpty() ? "현재 활성 수집기의 동작 상태이며 앱의 정상 또는 과거 전체 수집을 보장하지 않습니다."
                : "수집·전송이 불완전합니다. 로그 부재나 일부 지표만으로 서버 정상을 단정하지 마십시오.";
        if (anyDropped) warning += " 이 실행에서 데이터 폐기 이력이 있어 로그·지표가 누락되었을 수 있습니다.";
        String issues = problems.isEmpty() ? "" : "\n확인 항목: " + String.join(", ", problems);
        return "Forwarder: " + state + " — " + warning + issues + "\n실행 ID: " + report.runId()
                + " / 버전: " + report.version() + " / 마지막 서버 수신: " + latest.getReceivedAt()
                + "\n로그 큐: " + report.logQueueSize() + "/" + report.logQueueCapacity() + " 배치\n" + detail;
    }

    private String serialize(ForwarderStatusIngestDto report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("수집기 상태를 저장할 수 없습니다.", error);
        }
    }

    private ForwarderStatusIngestDto deserialize(String json) {
        try {
            return objectMapper.readValue(json, ForwarderStatusIngestDto.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("저장된 수집기 상태를 읽을 수 없습니다.", error);
        }
    }
}
