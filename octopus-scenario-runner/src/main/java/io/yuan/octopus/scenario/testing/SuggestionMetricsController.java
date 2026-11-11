package io.yuan.octopus.scenario.testing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static io.yuan.octopus.scenario.testing.TestingJson.read;
import static io.yuan.octopus.scenario.testing.TestingModel.Flow;

/**
 * Phase 1E：LLM 建议质量度量。
 * <ul>
 *   <li>{@code GET  /api/business-testing/suggestion-metrics?days=30}：按类型/模型汇总</li>
 *   <li>{@code POST /api/business-testing/suggestion-metrics/feedback}：前端上报采纳/拒绝/修改</li>
 *   <li>{@code POST /api/business-testing/suggestion-metrics/evaluate}：{@code {golden, candidate}} 黄金流程评估</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/business-testing/suggestion-metrics")
public class SuggestionMetricsController {
    public record EvaluateRequest(Flow golden, Flow candidate) {}

    private final SuggestionMetricsService service;

    public SuggestionMetricsController(SuggestionMetricsService service) {
        this.service = service;
    }

    @GetMapping
    public SuggestionMetricsService.Summary summary(@RequestParam(defaultValue = "30") int days) {
        return service.summary(days);
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody Map<String, Object> body) {
        service.feedback(read(body, SuggestionMetricsService.Feedback.class));
        return Map.of("recorded", true);
    }

    @PostMapping("/evaluate")
    public SuggestionMetricsService.Evaluation evaluate(@RequestBody Map<String, Object> body) {
        var request = read(body, EvaluateRequest.class);
        return service.evaluate(request.golden(), request.candidate());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
