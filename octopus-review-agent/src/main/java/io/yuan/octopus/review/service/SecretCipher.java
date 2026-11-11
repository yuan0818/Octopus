package io.yuan.octopus.review.service;

import io.yuan.octopus.infra.common.CredentialEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型 API Key 等敏感信息的静态加密，委托给全平台统一的 {@link CredentialEncryptor}
 * （与 code-intelligence、code-base、scenario-runner 同一实现、同一密钥、同一格式）。
 * <p>
 * 策略（fail closed）：
 * <ul>
 *   <li>密钥未配置：服务可以启动，已有明文历史值仍可读取，但<b>任何写入密钥的操作都会报错</b>，
 *       不再静默以明文落库。</li>
 *   <li>密钥长度不是 16/24/32 字节：启动失败（此前会用 SHA-256 隐式推导，导致与其他服务
 *       推导出的密钥不一致、跨服务无法互解）。</li>
 *   <li>无前缀的值视为历史明文，读取时原样返回；有密钥时由 ModelRegistry 启动时升级为密文。</li>
 * </ul>
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private final CredentialEncryptor encryptor;

    public SecretCipher(@Value("${review.security.secret-key:${OCTOPUS_ENCRYPTION_KEY:}}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("OCTOPUS_ENCRYPTION_KEY 未配置：模型 API Key 将无法保存（不会以明文落库）。"
                    + "请设置与其他 Octopus 服务一致的 16/24/32 字节密钥");
            this.encryptor = null;
            return;
        }
        // 长度不合法时 CredentialEncryptor 抛 IllegalArgumentException → 启动失败（fail-fast）
        this.encryptor = new CredentialEncryptor(secretKey);
    }

    /** 是否已配置加密密钥。 */
    public boolean available() {
        return encryptor != null;
    }

    public boolean isEncrypted(String value) {
        return CredentialEncryptor.isEncrypted(value);
    }

    /** 加密；空值与已加密值原样返回。未配置密钥时拒绝写入。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank() || isEncrypted(plain)) {
            return plain;
        }
        if (encryptor == null) {
            throw new IllegalStateException(
                    "尚未配置加密密钥 OCTOPUS_ENCRYPTION_KEY，无法保存模型 API Key（不会以明文存储）");
        }
        return encryptor.encrypt(plain);
    }

    /** 解密；明文值（历史数据）原样返回。 */
    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        if (encryptor == null) {
            log.error("存在加密的 API Key 但密钥未配置，无法解密");
            return null;
        }
        try {
            return encryptor.decrypt(stored);
        } catch (RuntimeException e) {
            throw new IllegalStateException("解密失败（密钥与加密时不一致？）", e);
        }
    }

    /** 展示用脱敏（全平台统一规则，见 {@link CredentialEncryptor#mask}）。 */
    public String mask(String plainOrEncrypted) {
        return CredentialEncryptor.mask(decrypt(plainOrEncrypted));
    }
}
