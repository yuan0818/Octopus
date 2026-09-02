package io.yuan.octopus.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.domain.DiscoveredProject;
import io.yuan.octopus.dto.request.CreateRepoConfigRequest;
import io.yuan.octopus.dto.request.UpdateRepoConfigRequest;
import io.yuan.octopus.dto.response.RepoConfigSummary;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.entity.RepoSnapshot;
import io.yuan.octopus.service.GitService;
import io.yuan.octopus.service.ProjectScanService;
import io.yuan.octopus.service.RepoConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoConfigService repoConfigService;
    private final GitService gitService;
    private final ProjectScanService projectScanService;

    public RepoController(RepoConfigService repoConfigService, GitService gitService,
                          ProjectScanService projectScanService) {
        this.repoConfigService = repoConfigService;
        this.gitService = gitService;
        this.projectScanService = projectScanService;
    }

    @PostMapping
    public Result<RepoConfig> create(@RequestBody CreateRepoConfigRequest request) {
        return Result.success(repoConfigService.create(request));
    }

    @GetMapping
    public Result<List<RepoConfigSummary>> listAll() {
        return Result.success(repoConfigService.listAll());
    }

    @GetMapping("/{id}")
    public Result<RepoConfig> getById(@PathVariable Long id) {
        return Result.success(repoConfigService.getById(id));
    }

    @PutMapping("/{id}")
    public Result<RepoConfig> update(@PathVariable Long id,
                                     @RequestBody UpdateRepoConfigRequest request) {
        return Result.success(repoConfigService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        repoConfigService.delete(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/sync")
    public Result<RepoSnapshot> sync(@PathVariable Long id) {
        RepoConfig repoConfig = repoConfigService.getById(id);
        RepoSnapshot snapshot = gitService.cloneOrPull(repoConfig);
        return Result.success(snapshot);
    }

    @GetMapping("/{id}/projects")
    public Result<List<DiscoveredProject>> scanProjects(@PathVariable Long id) {
        RepoConfig repoConfig = repoConfigService.getById(id);
        RepoSnapshot snapshot = gitService.cloneOrPull(repoConfig);
        List<DiscoveredProject> projects = projectScanService.scanProjects(snapshot);
        return Result.success(projects);
    }
}
