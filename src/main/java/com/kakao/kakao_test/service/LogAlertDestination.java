package com.kakao.kakao_test.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import com.kakao.kakao_test.repository.LogAlertRepository;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class LogAlertDestination {
    private static final int IV_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec webhookEncryptionKey;
    private final LogAlertRepository logAlertRepository;
    private final ApplicationEventPublisher eventPublisher;

    public record ChannelConfigured(long serverId) {}

    public void remember(long serverId, String url) {
        try {
            byte[] plain = url.getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plain));
            if (logAlertRepository.existsByServerIdAndWebhookHash(serverId, hash)) return;
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, serverId, iv).doFinal(plain);
            String stored = Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
            logAlertRepository.saveDestination(serverId, hash, stored);
            eventPublisher.publishEvent(new ChannelConfigured(serverId));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 암호화 실패", e);
        }
    }

    public String decode(long serverId, String stored) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(stored));
        byte[] iv = new byte[IV_BYTES];
        buffer.get(iv);
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        try {
            return new String(cipher(Cipher.DECRYPT_MODE, serverId, iv).doFinal(data), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 복호화 실패", e);
        }
    }

    private Cipher cipher(int mode, long serverId, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, webhookEncryptionKey, new GCMParameterSpec(128, iv));
        cipher.updateAAD(ByteBuffer.allocate(Long.BYTES).putLong(serverId).array());
        return cipher;
    }
}
