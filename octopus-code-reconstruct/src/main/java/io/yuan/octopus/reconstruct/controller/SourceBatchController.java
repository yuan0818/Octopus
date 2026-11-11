package io.yuan.octopus.reconstruct.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.reconstruct.service.GitSourceProvider;
import io.yuan.octopus.reconstruct.service.SourceResult;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按行范围批量从 git 读取源码片段。
 * <p>
 * 供 code-intelligence 的链路版本对比（entry-chain-diff）等消费方使用：
 * 坐标（file + 行号）来自元数据索引，内容一律从 git blob 读取——
 * git 是源码内容的唯一真相来源。
 */
@RestController
@RequestMapping("/api")
public class SourceBatchController {

    private final GitSourceProvider gitSourceProvider;

    public SourceBatchController(GitSourceProvider gitSourceProvider) {
        this.gitSourceProvider = gitSourceProvider;
    }

    public record BatchRequest(Long projectId, String commitId, List<Item> items) {}
    public record Item(String filePath, int lineStart, int lineEnd) {}
    public record ItemResult(boolean found, String content, String error) {}

    @GetMapping("/source/tree")
    public Result<List<String>> tree(@RequestParam long projectId, @RequestParam String commitId) {
        return Result.success(gitSourceProvider.tree(projectId, commitId));
    }

    @PostMapping("/source/batch")
    public Result<List<ItemResult>> batch(@RequestBody BatchRequest request) {
        if (request == null || request.projectId() == null || request.items() == null) {
            return Result.error(400, "projectId and items are required");
        }
        List<ItemResult> results = new ArrayList<>(request.items().size());
        for (Item item : request.items()) {
            SourceResult r = gitSourceProvider.getSource(
                    request.projectId(), request.commitId(),
                    item.filePath(), item.lineStart(), item.lineEnd());
            results.add(new ItemResult(r.found(), r.found() ? r.content() : null,
                    r.found() ? null : r.errorMsg()));
        }
        return Result.success(results);
    }
}
