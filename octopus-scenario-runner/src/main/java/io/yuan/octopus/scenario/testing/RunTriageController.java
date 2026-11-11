package io.yuan.octopus.scenario.testing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase 1D：失败运行的 LLM 建议性分析。
 * {@code POST /api/business-testing/runs/{id}/triage}（可选 {@code {model}}）生成并保存；{@code GET} 读取已保存结果。
 * 分析结果只作参考，不改变运行判定。
 */
@RestController
@RequestMapping("/api/business-testing/runs/{id}/triage")
public class RunTriageController {
    private final RunTriageService service;

    public RunTriageController(RunTriageService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<RunTriageService.View> get(@PathVariable String id) {
        return service.get(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public RunTriageService.View triage(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String model = body != null && body.get("model") instanceof String s && !s.isBlank() ? s : null;
        return service.triage(id, model);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(FlowFailure.class)
    public ResponseEntity<Map<String, Object>> model(FlowFailure e) {
        return ResponseEntity.status(503).body(Map.of("code", e.code == null ? "MODEL_UNAVAILABLE" : e.code.name(),
                "message", "模型调用失败，请根据错误类别检查配置或重试"));
    }
}
