package io.yuan.octopus.service;

import io.yuan.octopus.common.AuthType;
import io.yuan.octopus.dto.request.CreateRepoConfigRequest;
import io.yuan.octopus.dto.request.UpdateRepoConfigRequest;
import io.yuan.octopus.dto.response.RepoConfigSummary;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.exception.ResourceNotFoundException;
import io.yuan.octopus.exception.ValidationException;
import io.yuan.octopus.repository.RepoConfigRepository;
import io.yuan.octopus.utils.CredentialEncryptor;
import io.yuan.octopus.utils.GitUrlValidator;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RepoConfigService {

    private final RepoConfigRepository repoConfigRepository;
    private final GitUrlValidator gitUrlValidator;
    private final CredentialEncryptor credentialEncryptor;

    public RepoConfigService(RepoConfigRepository repoConfigRepository,
                             GitUrlValidator gitUrlValidator,
                             CredentialEncryptor credentialEncryptor) {
        this.repoConfigRepository = repoConfigRepository;
        this.gitUrlValidator = gitUrlValidator;
        this.credentialEncryptor = credentialEncryptor;
    }

    public RepoConfig create(CreateRepoConfigRequest request) {
        var validation = gitUrlValidator.validate(request.repoUrl());
        if (!validation.valid()) {
            throw new ValidationException(validation.errorMessage());
        }

        RepoConfig config = RepoConfig.builder()
                .repoUrl(request.repoUrl())
                .branch(request.branch() != null ? request.branch() : "main")
                .authType(request.authType() != null ? request.authType() : AuthType.NONE)
                .build();

        applyCredentials(config, request.authType(),
                request.httpUsername(), request.httpCredential(),
                request.sshPrivateKeyPath(), request.sshPassphrase(), request.sshKnownHostsPath());

        return repoConfigRepository.save(config);
    }

    public RepoConfig update(Long id, UpdateRepoConfigRequest request) {
        RepoConfig config = repoConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RepoConfig not found with id: " + id));

        if (request.repoUrl() != null) {
            var validation = gitUrlValidator.validate(request.repoUrl());
            if (!validation.valid()) {
                throw new ValidationException(validation.errorMessage());
            }
            config.setRepoUrl(request.repoUrl());
        }

        if (request.branch() != null) {
            config.setBranch(request.branch());
        }

        if (request.authType() != null) {
            config.setAuthType(request.authType());
        }

        applyCredentialsIfChanged(config,
                request.httpUsername(), request.httpCredential(),
                request.sshPrivateKeyPath(), request.sshPassphrase(), request.sshKnownHostsPath());

        return repoConfigRepository.save(config);
    }

    public void delete(Long id) {
        RepoConfig config = repoConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RepoConfig not found with id: " + id));
        repoConfigRepository.delete(config);
    }

    public RepoConfig getById(Long id) {
        return repoConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RepoConfig not found with id: " + id));
    }

    public List<RepoConfigSummary> listAll() {
        return repoConfigRepository.findAll().stream()
                .map(config -> new RepoConfigSummary(
                        config.getId(),
                        config.getRepoUrl(),
                        config.getBranch(),
                        config.getAuthType(),
                        config.getCreatedAt(),
                        config.getUpdatedAt()
                ))
                .toList();
    }

    private void applyCredentials(RepoConfig config, AuthType authType,
                                  String httpUsername, String httpCredential,
                                  String sshPrivateKeyPath, String sshPassphrase, String sshKnownHostsPath) {
        if (authType == AuthType.HTTP) {
            config.setHttpUsername(httpUsername);
            if (httpCredential != null && !httpCredential.isBlank()) {
                config.setHttpCredentialEncrypted(credentialEncryptor.encrypt(httpCredential));
            }
        } else if (authType == AuthType.SSH) {
            config.setSshPrivateKeyPath(sshPrivateKeyPath);
            config.setSshKnownHostsPath(sshKnownHostsPath);
            if (sshPassphrase != null && !sshPassphrase.isBlank()) {
                config.setSshPassphraseEncrypted(credentialEncryptor.encrypt(sshPassphrase));
            }
        }
    }

    private void applyCredentialsIfChanged(RepoConfig config,
                                           String httpUsername, String httpCredential,
                                           String sshPrivateKeyPath, String sshPassphrase, String sshKnownHostsPath) {
        if (httpUsername != null) {
            config.setHttpUsername(httpUsername);
        }
        if (httpCredential != null && !httpCredential.isBlank()) {
            config.setHttpCredentialEncrypted(credentialEncryptor.encrypt(httpCredential));
        }
        if (sshPrivateKeyPath != null) {
            config.setSshPrivateKeyPath(sshPrivateKeyPath);
        }
        if (sshPassphrase != null && !sshPassphrase.isBlank()) {
            config.setSshPassphraseEncrypted(credentialEncryptor.encrypt(sshPassphrase));
        }
        if (sshKnownHostsPath != null) {
            config.setSshKnownHostsPath(sshKnownHostsPath);
        }
    }
}
