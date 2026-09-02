package io.yuan.octopus.review.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 等敏感信息的静态加密（AES-256-GCM）。
 * <p>
 * 密文格式 {@code enc:v1:<base64(iv)>:<base64(ciphertext+tag)>}；
 * 无前缀的值视为历史明文，读取时原样返回（首次保存时自动升级为密文）。
 * 密钥来自 {@code review.security.secret-key}（默认取环境变量 OCTOPUS_ENCRYPTION_KEY，
 * 与 code-intelligence 的 CredentialEncryptor 约定一致）。密钥未配置时降级为明文存储并告警。
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${review.security.secret-key:${OCTOPUS_ENCRYPTION_KEY:}}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("review.security.secret-key 未配置：API Key 将以明文存储（设置 OCTOPUS_ENCRYPTION_KEY 启用加密）");
            this.key = null;
            return;
        }
        byte[] bytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            // 任意长度密钥哈希到 32 字节，避免 AES 密钥长度限制导致启动失败
            try {
                bytes = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bytes);
            } catch (Exception e) {
                throw new IllegalStateException("无法初始化密钥派生", e);
            }
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** 加密；未配置密钥时原样返回明文。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain) || key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv)
                    + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密；明文值（历史数据）原样返回。 */
    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        if (key == null) {
            log.error("存在加密的 API Key 但密钥未配置，无法解密");
            return null;
        }
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ct = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（密钥不一致?）", e);
        }
    }

    /** 展示用脱敏：只暴露是否存在与极短前缀。 */
    public String mask(String plainOrEncrypted) {
        String plain = decrypt(plainOrEncrypted);
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        return plain.length() <= 8 ? "********" : plain.substring(0, 4) + "****";
    }
}
