package io.yuan.octopus.scenario.testing;

import io.yuan.octopus.infra.common.CredentialEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fail closed: descriptive contexts work without a key; saving credentials never falls back to plaintext. */
@Component
public class ContextCredentialCipher {
    private final CredentialEncryptor cipher;
    public ContextCredentialCipher(@Value("${scenario.testing.encryption-key:${OCTOPUS_ENCRYPTION_KEY:}}") String key) {
        cipher = key == null || key.isBlank() ? null : new CredentialEncryptor(key);
    }
    public boolean available() { return cipher != null; }
    public String encrypt(String value) {
        if (cipher == null) throw new IllegalStateException("尚未配置凭据加密密钥 OCTOPUS_ENCRYPTION_KEY，请先配置服务密钥再保存连接凭据");
        return cipher.encrypt(value);
    }
    public String decrypt(String value) {
        if (cipher == null) throw new IllegalStateException("凭据加密密钥未配置");
        try { return cipher.decrypt(value); }
        catch (RuntimeException e) { throw new IllegalStateException("连接凭据无法解密，请检查服务密钥"); }
    }
}
