package com.kakao.kakao_test.service;

import com.kakao.kakao_test.domain.TargetServer;
import com.kakao.kakao_test.dto.RegisterServerRequest;
import com.kakao.kakao_test.dto.RegisterServerResponse;
import com.kakao.kakao_test.exception.DuplicationServer;
import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerRegisterService {

    private final TargetServerRepository targetServerRepository;
    /**
     * 서버 등록
     */
    @Transactional
    public RegisterServerResponse registerServer(RegisterServerRequest req) {
        if (req.getServerName() == null || req.getServerName().isBlank()) {
            throw new IllegalArgumentException("serverName은 필수입니다.");
        }
        duplicateServer(req.getServerName(), req.getUrl());
        // 토큰 생성
        String token = UUID.randomUUID().toString();

        TargetServer server = TargetServer.register(req,  token);

        targetServerRepository.save(server);
        log.info("✅ 서버 등록 완료: {}", server.getServerName());

        return new RegisterServerResponse(
                server.getServerName(),
                req.getUrl(),
                req.getHealthPath(),
                token
        );
    }

    /**
     * 서버 등록 전 "이름 + url" 이 중복되는지 탐색
     */
    private void duplicateServer(String serverName, String url) {
        if (targetServerRepository.findByServerNameAndServerUrl(serverName, url).isPresent()) {
            throw new DuplicationServer("이미 존재하는 서버입니다. 서버 이름 : " + serverName + ", url : " + url);
        }
    }

    /**
     * 서버 URL 수정
     */
    @Transactional
    public void updateServerUrl(String name, String url) {
        TargetServer server = targetServerRepository.getByServerName(name);
        server.updateUrl(url);
    }
}
