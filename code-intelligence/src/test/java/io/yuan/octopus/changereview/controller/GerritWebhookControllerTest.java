package io.yuan.octopus.changereview.controller;

import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import io.yuan.octopus.changereview.service.ChangeReviewProducer;
import io.yuan.octopus.changereview.service.ChangeReviewTaskService;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.infra.common.CredentialEncryptor;
import io.yuan.octopus.repository.RepoConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GerritWebhookControllerTest {

    @Mock private RepoConfigRepository repoConfigRepository;
    @Mock private ChangeReviewTaskRepository taskRepository;
    @Mock private ChangeReviewTaskService taskService;
    @Mock private ChangeReviewProducer producer;
    @Mock private CredentialEncryptor credentialEncryptor;

    private static final Long REPO_ID = 100L;
    private static final String SECRET = "webhook-secret";

    private GerritWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new GerritWebhookController(repoConfigRepository, taskRepository,
                taskService, producer, credentialEncryptor, true);
    }

    private RepoConfig repoConfig() {
        return RepoConfig.builder()
                .id(REPO_ID).repoUrl("https://gerrit.example.com/a/test-repo")
                .branch("main").platform("GERRIT")
                .platformUrl("https://gerrit.example.com")
                .webhookSecretEncrypted("ENC")
                .build();
    }

    private String validPayload() {
        return """
            {"type":"patchset-created",
             "change":{"project":"test-repo","branch":"main","number":123},
             "patchSet":{"number":2}}
            """;
    }

    // ── payload 校验 ────────────────────────────────────────────

    @Test
    void shouldReturn400OnInvalidJson() {
        var resp = controller.handleGerrit(SECRET, "not-json{");
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void shouldIgnoreUnknownEventType() {
        var resp = controller.handleGerrit(SECRET, "{\"type\":\"ref-updated\"}");
        assertEquals(202, resp.getStatusCode().value());
        verifyNoInteractions(taskService);
    }

    @Test
    void shouldReturn400WhenPatchsetMissing() {
        var resp = controller.handleGerrit(SECRET, """
            {"type":"patchset-created","change":{"project":"test-repo","number":123}}
            """);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void shouldReturn400WhenPatchsetIsJsonNull() {
        // patchset 为 JSON null 时不应抛 ClassCastException（500）
        var resp = controller.handleGerrit(SECRET, """
            {"type":"patchset-created","change":{"project":"test-repo","number":123},"patchset":null}
            """);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void shouldReturn400WhenChangeObjectMissing() {
        // change 对象整体缺失/为 null 时不应 NPE
        var resp = controller.handleGerrit(SECRET, """
            {"type":"patchset-created","project":"test-repo","patchSet":{"number":2}}
            """);
        assertEquals(400, resp.getStatusCode().value());
    }

    // ── 仓库匹配 + 鉴权 ─────────────────────────────────────────

    @Test
    void shouldIgnoreUnconfiguredRepo() {
        when(repoConfigRepository.findByPlatformAndRepoUrlContaining("GERRIT", "test-repo"))
                .thenReturn(List.of());

        var resp = controller.handleGerrit(SECRET, validPayload());
        assertEquals(202, resp.getStatusCode().value());
        verifyNoInteractions(taskService);
    }

    @Test
    void shouldReturn401WhenTokenMismatch() {
        when(repoConfigRepository.findByPlatformAndRepoUrlContaining("GERRIT", "test-repo"))
                .thenReturn(List.of(repoConfig()));
        when(credentialEncryptor.decrypt("ENC")).thenReturn(SECRET);

        var resp = controller.handleGerrit("wrong-token", validPayload());
        assertEquals(401, resp.getStatusCode().value());
        verifyNoInteractions(taskService);
    }

    @Test
    void shouldReturn401WhenSecretNotConfigured() {
        RepoConfig noSecret = repoConfig();
        noSecret.setWebhookSecretEncrypted(null);
        when(repoConfigRepository.findByPlatformAndRepoUrlContaining("GERRIT", "test-repo"))
                .thenReturn(List.of(noSecret));

        var resp = controller.handleGerrit(SECRET, validPayload());
        assertEquals(401, resp.getStatusCode().value());
    }

    // ── 幂等 + 创建 ─────────────────────────────────────────────

    @Test
    void shouldReturnExistingTaskOnIdempotentRedelivery() {
        when(repoConfigRepository.findByPlatformAndRepoUrlContaining("GERRIT", "test-repo"))
                .thenReturn(List.of(repoConfig()));
        when(credentialEncryptor.decrypt("ENC")).thenReturn(SECRET);
        ChangeReviewTask existing = ChangeReviewTask.builder()
                .id(9L).status("COMPLETED").build();
        when(taskRepository.findByRepoConfigIdAndChangeNumberAndPatchsetNumber(REPO_ID, "123", 2))
                .thenReturn(Optional.of(existing));

        var resp = controller.handleGerrit(SECRET, validPayload());

        assertEquals(202, resp.getStatusCode().value());
        verifyNoInteractions(taskService);
        verifyNoInteractions(producer);
    }

    @Test
    void shouldCreateSupersedeAndPublishOnValidEvent() {
        when(repoConfigRepository.findByPlatformAndRepoUrlContaining("GERRIT", "test-repo"))
                .thenReturn(List.of(repoConfig()));
        when(credentialEncryptor.decrypt("ENC")).thenReturn(SECRET);
        when(taskRepository.findByRepoConfigIdAndChangeNumberAndPatchsetNumber(REPO_ID, "123", 2))
                .thenReturn(Optional.empty());
        when(taskService.createAndSupersede(any())).thenAnswer(inv -> {
            ChangeReviewTask created = inv.getArgument(0);
            created.setId(5L); // publish 响应体 Map.of 不允许 null taskId
            return created;
        });

        var resp = controller.handleGerrit(SECRET, validPayload());

        assertEquals(202, resp.getStatusCode().value());
        ArgumentCaptor<ChangeReviewTask> captor = ArgumentCaptor.forClass(ChangeReviewTask.class);
        verify(taskService).createAndSupersede(captor.capture());
        ChangeReviewTask created = captor.getValue();
        assertEquals(REPO_ID, created.getRepoConfigId());
        assertEquals("123", created.getChangeNumber());
        assertEquals(2, created.getPatchsetNumber());
        assertEquals("main", created.getTargetBranch());
        assertEquals("PENDING", created.getStatus());
        assertEquals("WEBHOOK", created.getTriggerSource());
        verify(producer).publish(any());
    }

    @Test
    void shouldMarkFailedWhenPublishFails() {
        when(repoConfigRepository.findByPlatformAndRepoUrlContaining("GERRIT", "test-repo"))
                .thenReturn(List.of(repoConfig()));
        when(credentialEncryptor.decrypt("ENC")).thenReturn(SECRET);
        when(taskRepository.findByRepoConfigIdAndChangeNumberAndPatchsetNumber(REPO_ID, "123", 2))
                .thenReturn(Optional.empty());
        when(taskService.createAndSupersede(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("mq down")).when(producer).publish(any());

        var resp = controller.handleGerrit(SECRET, validPayload());

        assertEquals(502, resp.getStatusCode().value());
        ArgumentCaptor<ChangeReviewTask> captor = ArgumentCaptor.forClass(ChangeReviewTask.class);
        verify(taskRepository).save(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
    }
}
