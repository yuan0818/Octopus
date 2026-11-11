package io.yuan.octopus.scenario.testing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static io.yuan.octopus.scenario.testing.TestingJson.read;

/**
 * 变更触发的批量回归（Phase 1A）。
 * <p>
 * {@code POST /api/business-testing/regression-batches}：
 * {@code {projectIds, branch, baseCommitId, targetCommitId, environment, timeoutSeconds?, initialContext?,
 * secretEnvironmentNames?, triggerSource?, externalRef?, idempotencyKey?, callbackUrl?}} → 202 + 批次摘要。
 * 影响分析、场景生成和执行在后台推进；轮询 {@code GET /{id}} 或通过 callbackUrl 接收最终摘要。
 */
@RestController
@RequestMapping("/api/business-testing/regression-batches")
public class RegressionBatchController {
    private final RegressionBatchService service;

    public RegressionBatchController(RegressionBatchService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RegressionBatchService.Summary> start(@RequestBody Map<String, Object> body) {
        return ResponseEntity.accepted().body(service.start(read(body, RegressionBatchService.StartRequest.class)));
    }

    @GetMapping
    public List<RegressionBatchService.Summary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public RegressionBatchService.Summary get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/cancel")
    public RegressionBatchService.Summary cancel(@PathVariable String id) {
        return service.cancel(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
