package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.dto.*;
import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import io.yuan.octopus.domain.MergeResult;
import io.yuan.octopus.entity.AnalysisTask;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.service.AnalysisOrchestrationService;
import io.yuan.octopus.service.GitService;
import io.yuan.octopus.service.RepoConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChangeReviewOrchestratorTest {

    @Mock private ChangeReviewTaskRepository taskRepository;
    @Mock private RepoConfigService repoConfigService;
    @Mock private GitService gitService;
    @Mock private AnalysisOrchestrationService analysisService;
    @Mock private ChangeMethodDetector changeMethodDetector;
    @Mock private AffectedEndpointService affectedEndpointService;
    @Mock private ReviewAgentClient reviewAgentClient;
    @Mock private GerritRestClient gerritRestClient;
    @Mock private GerritCommentBuilder commentBuilder;

    private ChangeReviewOrchestrator orchestrator;

    private static final Long TASK_ID = 1L;
    private static final Long REPO_CONFIG_ID = 100L;
    private static final String BRANCH = "main";
    private static final String BASE = "abc123";
    private static final String PATCHSET_COMMIT = "def456";
    private static final String MERGE = "ghi789";

    @BeforeEach
    void setUp() {
        orchestrator = new ChangeReviewOrchestrator(taskRepository, repoConfigService,
                gitService, analysisService, changeMethodDetector, affectedEndpointService,
                reviewAgentClient, gerritRestClient, commentBuilder,
                "http://localhost:8080");
    }

    @Test
    void shouldCompleteHappyPath() {
        ChangeReviewTask task = buildTask(null);
        RepoConfig rc = buildRepoConfig();
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(repoConfigService.getById(REPO_CONFIG_ID)).thenReturn(rc);
        when(gitService.localRepositoryPath(rc)).thenReturn(Path.of("/data/repos/test"));
        when(gitService.fetchPatchsetRef(rc, "123", 2)).thenReturn(PATCHSET_COMMIT);
        when(gitService.mergePatchsetOntoBase(rc, BRANCH, PATCHSET_COMMIT))
                .thenReturn(MergeResult.merged(BASE, PATCHSET_COMMIT, MERGE, Path.of("/data/repos/test")));
        when(analysisService.startChangeAnalysis(eq(REPO_CONFIG_ID), eq(BRANCH), eq(MERGE), any()))
                .thenReturn(AnalysisTask.builder().id(99L).build());

        ChangedMethod cm = new ChangedMethod(10L, "com.New", "new", "sig_new",
                "/f/New.java", "ADDED");
        when(changeMethodDetector.detect(any(), eq(BASE), eq(PATCHSET_COMMIT), eq(MERGE)))
                .thenReturn(List.of(cm));

        AffectedEndpoint ae = new AffectedEndpoint(10L, "com.New", "new", "sig_new",
                "GET", "/api/new", List.of("sig_new"),
                List.of(List.of("sig_new", "sig_new")));
        when(affectedEndpointService.getAffectedEndpoints(BRANCH, MERGE, List.of(cm)))
                .thenReturn(List.of(ae));

        EndpointReviewResult result = new EndpointReviewResult("rev_1", "NORMAL", "INFO",
                List.of(), null);
        when(reviewAgentClient.reviewEndpoint(10L, "com.New", "new", BRANCH, MERGE))
                .thenReturn(result);

        when(commentBuilder.build(anyList(), anyMap(), anyList(), eq("http://localhost:8080"), eq(TASK_ID)))
                .thenReturn("## Octopus report\nok");
        when(gerritRestClient.postComment(rc, "123", "## Octopus report\nok")).thenReturn(true);

        ChangeReviewTask finished = orchestrator.run(TASK_ID);

        assertEquals("COMPLETED", finished.getStatus());
        // WRITEBACK phase posts one comment with the review report (no conflict/empty-changes early-exit)
        verify(gerritRestClient, times(1)).postComment(any(), anyString(), anyString());
        // verify the task fields are persisted
        verify(taskRepository, atLeast(3)).save(any(ChangeReviewTask.class));
    }

    @Test
    void shouldFailOnMergeConflict() {
        ChangeReviewTask task = buildTask(null);
        RepoConfig rc = buildRepoConfig();
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(repoConfigService.getById(REPO_CONFIG_ID)).thenReturn(rc);
        when(gitService.localRepositoryPath(rc)).thenReturn(Path.of("/data/repos/test"));
        when(gitService.fetchPatchsetRef(rc, "123", 2)).thenReturn(PATCHSET_COMMIT);
        when(gitService.mergePatchsetOntoBase(rc, BRANCH, PATCHSET_COMMIT))
                .thenReturn(MergeResult.conflict(BASE, PATCHSET_COMMIT, Path.of("/data/repos/test"),
                        "main@abc"));

        ChangeReviewTask finished = orchestrator.run(TASK_ID);

        assertEquals("FAILED", finished.getStatus());
        assertEquals("MERGE_CONFLICT", finished.getFailureType());
        verify(gerritRestClient).postComment(eq(rc), eq("123"), contains("合入冲突"));
        verify(analysisService, never()).startChangeAnalysis(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void shouldCompleteOnNoJavaChanges() {
        ChangeReviewTask task = buildTask(ChangeReviewOrchestrator.PHASE_DETECT_CHANGE); // resume
        RepoConfig rc = buildRepoConfig();
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(repoConfigService.getById(REPO_CONFIG_ID)).thenReturn(rc);
        when(gitService.localRepositoryPath(rc)).thenReturn(Path.of("/data/repos/test"));

        when(changeMethodDetector.detect(any(), eq(BASE), eq(PATCHSET_COMMIT), eq(MERGE)))
                .thenReturn(List.of());

        ChangeReviewTask finished = orchestrator.run(TASK_ID);

        assertEquals("COMPLETED", finished.getStatus());
        verify(gerritRestClient).postComment(eq(rc), eq("123"), contains("无 Java 代码变更"));
        verify(affectedEndpointService, never()).getAffectedEndpoints(anyString(), anyString(), anyList());
    }

    @Test
    void shouldFailOnWritebackFailure() {
        ChangeReviewTask task = buildTask(null);
        RepoConfig rc = buildRepoConfig();
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(repoConfigService.getById(REPO_CONFIG_ID)).thenReturn(rc);
        when(gitService.localRepositoryPath(rc)).thenReturn(Path.of("/data/repos/test"));

        // FETCH_MERGE success
        when(gitService.fetchPatchsetRef(rc, "123", 2)).thenReturn(PATCHSET_COMMIT);
        when(gitService.mergePatchsetOntoBase(rc, BRANCH, PATCHSET_COMMIT))
                .thenReturn(MergeResult.merged(BASE, PATCHSET_COMMIT, MERGE, Path.of("/data/repos/test")));
        when(analysisService.startChangeAnalysis(eq(REPO_CONFIG_ID), eq(BRANCH), eq(MERGE), any()))
                .thenReturn(AnalysisTask.builder().id(99L).build());

        ChangedMethod cm = changedMethod("ADDED");
        when(changeMethodDetector.detect(any(), any(), any(), any())).thenReturn(List.of(cm));

        AffectedEndpoint ae = affectedEndpoint();
        when(affectedEndpointService.getAffectedEndpoints(any(), any(), any())).thenReturn(List.of(ae));
        when(reviewAgentClient.reviewEndpoint(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new EndpointReviewResult("rev", "NORMAL", null, List.of(), null));
        when(commentBuilder.build(anyList(), anyMap(), anyList(), anyString(), anyLong())).thenReturn("report");
        when(gerritRestClient.postComment(rc, "123", "report")).thenReturn(false);

        ChangeReviewTask finished = orchestrator.run(TASK_ID);
        // 审查结果已持久化，仅评论回写失败 → PARTIALLY_COMPLETED（/rewrite 恢复 WRITEBACK）
        assertEquals("PARTIALLY_COMPLETED", finished.getStatus());
        assertEquals("CALLBACK_FAILED", finished.getFailureType());
        assertEquals(ChangeReviewOrchestrator.PHASE_WRITEBACK, finished.getCurrentPhase());
    }

    @Test
    void shouldReusePersistedResultsOnWritebackResume() {
        // /rewrite 恢复：currentPhase=WRITEBACK + 已持久化 review_results → 不重跑 REVIEW
        Map<String, EndpointReviewResult> persisted = Map.of(
                "sig_x", new EndpointReviewResult("rev", "NORMAL", "WARNING",
                        List.of(new ReviewIssue("WARNING", "PERFORMANCE", "slow", "cache")), null));
        ChangeReviewTask task = buildTask(ChangeReviewOrchestrator.PHASE_WRITEBACK);
        task.setReviewResults(new com.google.gson.Gson().toJson(persisted));
        RepoConfig rc = buildRepoConfig();
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(repoConfigService.getById(REPO_CONFIG_ID)).thenReturn(rc);
        when(gitService.localRepositoryPath(rc)).thenReturn(Path.of("/data/repos/test"));

        when(changeMethodDetector.detect(any(), any(), any(), any()))
                .thenReturn(List.of(changedMethod("MODIFIED")));
        when(affectedEndpointService.getAffectedEndpoints(any(), any(), any()))
                .thenReturn(List.of(affectedEndpoint()));
        when(commentBuilder.build(anyList(), anyMap(), anyList(), anyString(), anyLong())).thenReturn("report");
        when(gerritRestClient.postComment(rc, "123", "report")).thenReturn(true);

        ChangeReviewTask finished = orchestrator.run(TASK_ID);

        assertEquals("COMPLETED", finished.getStatus());
        verify(reviewAgentClient, never()).reviewEndpoint(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldResumeFromAnalyzeWhenPhaseSet() {
        // Task already past FETCH_MERGE; should skip it and proceed from ANALYZE
        ChangeReviewTask task = buildTask(ChangeReviewOrchestrator.PHASE_ANALYZE);
        RepoConfig rc = buildRepoConfig();
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(repoConfigService.getById(REPO_CONFIG_ID)).thenReturn(rc);
        when(gitService.localRepositoryPath(rc)).thenReturn(Path.of("/data/repos/test"));

        // FETCH_MERGE should NOT be called (phase skip)
        when(analysisService.startChangeAnalysis(eq(REPO_CONFIG_ID), eq(BRANCH), eq(MERGE), any()))
                .thenReturn(AnalysisTask.builder().id(99L).build());

        ChangedMethod cm = changedMethod("MODIFIED");
        when(changeMethodDetector.detect(any(), any(), any(), any())).thenReturn(List.of(cm));
        AffectedEndpoint ae = affectedEndpoint();
        when(affectedEndpointService.getAffectedEndpoints(any(), any(), any())).thenReturn(List.of(ae));
        when(reviewAgentClient.reviewEndpoint(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new EndpointReviewResult("rev", "NORMAL", null, List.of(), null));
        when(commentBuilder.build(anyList(), anyMap(), anyList(), anyString(), anyLong())).thenReturn("rpt");
        when(gerritRestClient.postComment(any(), any(), any())).thenReturn(true);

        ChangeReviewTask finished = orchestrator.run(TASK_ID);
        assertEquals("COMPLETED", finished.getStatus());
        verify(gitService, never()).fetchPatchsetRef(any(), anyString(), anyInt());
        verify(gitService, never()).mergePatchsetOntoBase(any(), anyString(), anyString());
    }

    // ── helpers ──

    private ChangeReviewTask buildTask(String currentPhase) {
        return ChangeReviewTask.builder()
                .id(TASK_ID).repoConfigId(REPO_CONFIG_ID).platform("GERRIT")
                .changeNumber("123").patchsetNumber(2).targetBranch(BRANCH)
                .baseCommitId(BASE).patchsetCommitId(PATCHSET_COMMIT).mergeCommitId(MERGE)
                .status("RUNNING").triggerSource("WEBHOOK").currentPhase(currentPhase)
                .build();
    }

    private RepoConfig buildRepoConfig() {
        return RepoConfig.builder().id(REPO_CONFIG_ID).repoUrl("https://gerrit.example.com/a/test")
                .branch(BRANCH).platform("GERRIT").platformUrl("https://gerrit.example.com")
                .platformTokenEncrypted("ENC").webhookSecretEncrypted("ENC").build();
    }

    private ChangedMethod changedMethod(String changeType) {
        return new ChangedMethod(10L, "com.X", "m", "sig_x", "/f/X.java", changeType);
    }

    private AffectedEndpoint affectedEndpoint() {
        return new AffectedEndpoint(10L, "com.X", "m", "sig_x",
                "GET", "/api/x", List.of("sig_x"), List.of(List.of("sig_x")));
    }
}
