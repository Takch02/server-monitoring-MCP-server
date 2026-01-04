package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.LogEventDto;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LogService {

    // TODO: 타임아웃 설정 권장(데모는 기본 RestClient)
    private final RestClient restClient = RestClient.create();

    private final Map<String, TargetServer> serverStore = new ConcurrentHashMap<>();

    // 서버별 최근 로그 링버퍼
    private final Map<String, Deque<LogEventDto>> logBuffers = new ConcurrentHashMap<>();
    private static final int MAX_LOGS_PER_SERVER = 10_000;


}
