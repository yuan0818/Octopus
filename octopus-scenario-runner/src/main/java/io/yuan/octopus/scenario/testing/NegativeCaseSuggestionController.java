package io.yuan.octopus.scenario.testing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase 2.3：{@code POST /api/business-testing/case-candidates/negative-suggestions}
 * {@code {flowId, flowRevision, testPointId, limit?, goal?, model?}} → LLM 负向用例草稿（不保存）。
 */
@RestController
@RequestMapping("/api/business-testing/case-candidates/negative-suggestions")
public class NegativeCaseSuggestionController {
    private final NegativeCaseSuggestionService service;

    public NegativeCaseSuggestionController(NegativeCaseSuggestionService service) {
        this.service = service;
    }

    @PostMapping
    public NegativeCaseSuggestionService.Suggestion suggest(@RequestBody NegativeCaseSuggestionService.Request request) {
        return service.suggest(request);
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
