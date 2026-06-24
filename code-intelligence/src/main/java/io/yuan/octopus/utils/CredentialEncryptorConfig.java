package io.yuan.octopus.utils;

import io.yuan.octopus.infra.common.CredentialEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link io.yuan.octopus.infra.common.CredentialEncryptor} as a Spring-managed bean
 * configured from {@code octopus.encryption.secret-key}.
 *
 * @see io.yuan.octopus.infra.common.CredentialEncryptor
 */
@Configuration
public class CredentialEncryptorConfig {

    @Bean
    public CredentialEncryptor credentialEncryptor(
            @Value("${octopus.encryption.secret-key}") String key) {
        return new CredentialEncryptor(key);
    }
}
