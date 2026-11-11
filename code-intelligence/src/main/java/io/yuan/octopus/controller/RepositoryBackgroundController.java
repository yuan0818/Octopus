package io.yuan.octopus.controller;
import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.service.RepositoryBackgroundService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/query/repository-background")
public class RepositoryBackgroundController {
    private final RepositoryBackgroundService service;
    public RepositoryBackgroundController(RepositoryBackgroundService service){this.service=service;}
    @GetMapping public Result<RepositoryBackgroundService.Packet> collect(@RequestParam String key,@RequestParam String branch,@RequestParam String commitId,@RequestParam String environment){return Result.success(service.collect(key,branch,commitId,environment));}
}
