package io.yuan.octopus.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.dto.request.LocalAnalysisRequest;
import io.yuan.octopus.dto.response.AnalysisTaskDTO;
import io.yuan.octopus.entity.AnalysisTask;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.service.AnalysisOrchestrationService;
import io.yuan.octopus.service.AnalysisTaskService;
import io.yuan.octopus.service.GitService;
import io.yuan.octopus.service.RepoConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisOrchestrationService analysisOrchestrationService;
    private final RepoConfigService repoConfigService;
    private final GitService gitService;
    private final AnalysisTaskService analysisTaskService;

    public AnalysisController(AnalysisOrchestrationService analysisOrchestrationService,
                              RepoConfigService repoConfigService,
                              GitService gitService,
                              AnalysisTaskService analysisTaskService) {
        this.analysisOrchestrationService = analysisOrchestrationService;
        this.repoConfigService = repoConfigService;
        this.gitService = gitService;
        this.analysisTaskService = analysisTaskService;
    }

    /**
     * Start (or incrementally update) analysis for a specific Git tag.
     * <p>
     * Example: {@code POST /api/analysis/1?tag=v1.2.0}
     */
    @PostMapping("/{repoConfigId}")
    public Result<AnalysisTaskDTO> startAnalysis(@PathVariable Long repoConfigId,
                                                 @RequestParam String tag) {
        AnalysisTask task = analysisOrchestrationService.startAnalysis(repoConfigId, tag);
        return Result.success(analysisTaskService.toDTO(task));
    }

    /**
     * Start a directed-graph analysis on a local directory (no Git repository needed).
     * <p>
     * Example: {@code POST /api/analysis/local}
     * <pre>{@code
     * { "projectPath": "/path/to/java/project", "projectName": "optional-name" }
     * }</pre>
     */
    @PostMapping("/local")
    public Result<AnalysisTaskDTO> startLocalAnalysis(@RequestBody LocalAnalysisRequest request) {
        AnalysisTask task = analysisOrchestrationService.startLocalAnalysis(request);
        return Result.success(analysisTaskService.toDTO(task));
    }

    /**
     * List all Git tags available for a repository (newest first).
     * <p>
     * Example: {@code GET /api/analysis/1/tags}
     */
    @GetMapping("/{repoConfigId}/tags")
    public Result<List<String>> listTags(@PathVariable Long repoConfigId) {
        RepoConfig repoConfig = repoConfigService.getById(repoConfigId);
        List<String> tags = gitService.listTags(repoConfig);
        return Result.success(tags);
    }

    @GetMapping("/tasks/{taskId}")
    public Result<AnalysisTaskDTO> getTaskStatus(@PathVariable Long taskId) {
        return Result.success(analysisTaskService.getTaskDetails(taskId));
    }

    @GetMapping("/tasks")
    public Result<List<AnalysisTaskDTO>> listTasks() {
        return Result.success(analysisTaskService.listTasks());
    }

    @PostMapping("/tasks/{taskId}/retry")
    public Result<AnalysisTaskDTO> retryAnalysis(@PathVariable Long taskId) {
        AnalysisTask task = analysisOrchestrationService.retryAnalysis(taskId);
        return Result.success(analysisTaskService.toDTO(task));
    }
}
