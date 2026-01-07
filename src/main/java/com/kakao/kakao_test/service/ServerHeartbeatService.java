package com.kakao.kakao_test.service;

import com.kakao.kakao_test.repository.TargetServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServerHeartbeatService {

    private final TargetServerRepository targetServerRepository;

    /**
     * 부모 트랜잭션(ingestLogs)이 있더라도, 새로운 트렌젝션에서 수행.
     * 이 메서드가 끝나면 즉시 Commit 되고, X-Lock도 즉시 반납됨.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateHeartbeatQuickly(Long serverId) {
        targetServerRepository.updateHeartbeatNow(serverId);
    }
}