package io.yuan.octopus.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.service.RepositoryScopeService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/query/repository-scopes")
public class RepositoryScopeController {
    private final RepositoryScopeService service;
    public RepositoryScopeController(RepositoryScopeService service) { this.service = service; }

    @GetMapping
    public Result<List<RepositoryScopeService.Scope>> scopes() { return Result.success(service.scopes()); }

    @GetMapping("/branches")
    public Result<List<String>> branches(@RequestParam String key) {
        return Result.success(service.branches(key));
    }

    @GetMapping("/versions")
    public Result<List<RepositoryScopeService.Version>> versions(@RequestParam String key,
            @RequestParam String branch, @RequestParam(required = false) List<Long> projectIds) {
        return Result.success(service.versions(key, branch, projectIds));
    }
}
