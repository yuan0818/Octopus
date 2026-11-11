package io.yuan.octopus.infra.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 全平台统一的 AES-GCM 凭据加解密（Git 凭据、Gerrit token、webhook secret、
 * 模型 API Key、业务测试连接凭据都走这里）。
 * <p>
 * <b>密钥</b>：{@code OCTOPUS_ENCRYPTION_KEY}，UTF-8 编码后必须恰好 16/24/32 字节，
 * 否则构造时直接失败（fail-fast，不做任何隐式推导）。所有服务必须使用同一个密钥。
 * <p>
 * <b>密文格式</b>：
 * <ul>
 *   <li>当前写入格式 {@code enc:v1:<base64(iv)>:<base64(ciphertext+tag)>}——自描述，
 *       便于识别与日后轮换（v2）。</li>
 *   <li>历史格式 {@code base64(iv ‖ ciphertext+tag)}（code-intelligence 早期写入），
 *       解密时自动识别，无需迁移。</li>
 * </ul>
 * Plain class——由各模块用配置的密钥自行实例化。
 */
public class CredentialEncryptor {

    /** 当前版本密文前缀。 */
    public static final String PREFIX_V1 = "enc:v1:";

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec secretKey;

    public CredentialEncryptor(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                "Encryption key is not configured (set OCTOPUS_ENCRYPTION_KEY, 16/24/32 bytes)");
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "AES key must be 16, 24, or 32 bytes (UTF-8 encoded). Got: " + keyBytes.length + " bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /** 值是否为 v1 格式密文（历史格式无前缀，无法可靠识别，返回 false）。 */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX_V1);
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            throw new IllegalArgumentException("plainText must not be null");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            return PREFIX_V1 + b64.encodeToString(iv) + ":" + b64.encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt credential", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) {
            throw new IllegalArgumentException("encryptedText must not be null");
        }
        try {
            byte[] iv;
            byte[] encrypted;
            if (isEncrypted(encryptedText)) {
                String[] parts = encryptedText.substring(PREFIX_V1.length()).split(":", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Malformed v1 ciphertext");
                }
                iv = Base64.getDecoder().decode(parts[0]);
                encrypted = Base64.getDecoder().decode(parts[1]);
            } else {
                // 历史格式：base64(iv ‖ ciphertext+tag)
                byte[] combined = Base64.getDecoder().decode(encryptedText);
                if (combined.length <= GCM_IV_LENGTH) {
                    throw new IllegalArgumentException(
                        "Ciphertext too short: expected at least " + (GCM_IV_LENGTH + 1)
                        + " bytes, got " + combined.length);
                }
                iv = new byte[GCM_IV_LENGTH];
                System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
                encrypted = new byte[combined.length - GCM_IV_LENGTH];
                System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt credential", e);
        }
    }

    /**
     * 统一的展示脱敏规则：只暴露是否存在，以及长值的前 4 个字符。
     * 入参应为明文；空值返回空串。
     */
    public static String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        return plain.length() <= 12 ? "********" : plain.substring(0, 4) + "****";
    }
}
