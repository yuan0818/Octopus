package io.yuan.octopus.git.controller;

import io.yuan.octopus.git.dto.*;
import io.yuan.octopus.git.service.GitRepositoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/git")
public class GitController {

    private final GitRepositoryService gitService;

    public GitController(GitRepositoryService gitService) {
        this.gitService = gitService;
    }

    @PostMapping("/sync")
    public RepoSnapshotDto sync(@RequestBody GitRepoConfig config) {
        return gitService.cloneOrPull(config);
    }

    @PostMapping("/checkout")
    public RepoSnapshotDto checkout(@RequestBody CheckoutRequest req) {
        GitRepoConfig config = new GitRepoConfig(
                req.repoUrl(), req.branch(), req.authType(),
                req.httpUsername(), req.httpPassword(),
                req.sshPrivateKeyPath(), req.sshPassphrase(), req.sshKnownHostsPath());
        return gitService.syncAndCheckout(config, req.tag());
    }

    @GetMapping("/{repoName}/tags")
    public List<String> listTags(@PathVariable String repoName,
                                  @RequestParam String repoUrl,
                                  @RequestParam(defaultValue = "main") String branch) {
        GitRepoConfig config = new GitRepoConfig(repoUrl, branch, "NONE", null, null, null, null, null);
        return gitService.listTags(config);
    }

    @PostMapping("/{repoName}/diff")
    public Optional<DiffResultDto> diff(@PathVariable String repoName, @RequestBody DiffRequest req) {
        return gitService.computeDiff(repoName, req.fromTag(), req.toTag());
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
