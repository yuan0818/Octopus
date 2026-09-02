package io.yuan.octopus.codebase.config;

import io.yuan.octopus.infra.common.CredentialEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 暴露 {@link CredentialEncryptor} 为 Spring bean，配置来自 {@code octopus.encryption.secret-key}。
 * 与 code-intelligence 使用同一密钥环境变量（{@code OCTOPUS_ENCRYPTION_KEY}），保证平台凭证
 * 在 code-base 写入加密、在 code-intelligence 读取解密互通（ADR-003）。
 */
@Configuration
public class CredentialEncryptorConfig {

    @Bean
    public CredentialEncryptor credentialEncryptor(
            @Value("${octopus.encryption.secret-key}") String key) {
        return new CredentialEncryptor(key);
    }
}
