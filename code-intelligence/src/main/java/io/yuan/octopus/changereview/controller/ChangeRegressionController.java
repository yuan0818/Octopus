package io.yuan.octopus.changereview.controller;

import io.yuan.octopus.changereview.service.ChangeRegressionService;
import io.yuan.octopus.infra.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 变更审查 → 业务回归（Phase 1B）。
 * <ul>
 *   <li>{@code POST /api/change-review/{taskId}/regression}：{@code {environment?, timeoutSeconds?}}，
 *       用任务的 base / merge commit 发起批量回归（建议由 CI 在部署测试环境后调用）。</li>
 *   <li>{@code POST /api/change-review/regression-callback}：scenario-runner 批次结束回调，
 *       头 {@code X-Octopus-Callback-Token} 必须与 {@code octopus.change-review.regression.callback-token} 一致。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/change-review")
public class ChangeRegressionController {

    private final ChangeRegressionService service;

    public ChangeRegressionController(ChangeRegressionService service) {
        this.service = service;
    }

    @PostMapping("/{taskId}/regression")
    public ResponseEntity<Result<Map<String, Object>>> trigger(@PathVariable Long taskId,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        String environment = request.get("environment") instanceof String s ? s : null;
        Long timeout = request.get("timeoutSeconds") instanceof Number n ? n.longValue() : null;
        return ResponseEntity.accepted().body(Result.success(service.trigger(taskId, environment, timeout)));
    }

    @PostMapping("/regression-callback")
    public ResponseEntity<Result<Map<String, Object>>> callback(
            @RequestHeader(value = "X-Octopus-Callback-Token", required = false) String token,
            @RequestBody Map<String, Object> summary) {
        boolean posted = service.onCallback(token, summary);
        // 回写失败返回 502，scenario-runner 会按退避重试
        return posted ? ResponseEntity.ok(Result.success(Map.of("posted", true)))
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.error(502, "GERRIT_WRITEBACK_FAILED"));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<Void>> unauthorized(SecurityException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(401, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.error(503, e.getMessage()));
    }
}
