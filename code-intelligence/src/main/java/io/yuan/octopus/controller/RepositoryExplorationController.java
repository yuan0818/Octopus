package io.yuan.octopus.controller;
import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.service.RepositoryExplorationService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/query/repository-exploration")
public class RepositoryExplorationController {
    private final RepositoryExplorationService service;
    public RepositoryExplorationController(RepositoryExplorationService service){this.service=service;}
    @GetMapping public Result<?> inventory(@RequestParam String key,@RequestParam String branch,@RequestParam String commitId,@RequestParam(required=false) String environment){return Result.success(service.inventory(key,branch,commitId,environment));}
    @PostMapping("/read") public Result<?> read(@RequestBody RepositoryExplorationService.ReadRequest request){return Result.success(service.read(request));}
}
