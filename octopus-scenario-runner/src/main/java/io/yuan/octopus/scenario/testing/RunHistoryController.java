package io.yuan.octopus.scenario.testing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Phase 2.5：{@code GET /api/business-testing/history?flowId=&testPointId=&limit=50}。 */
@RestController
@RequestMapping("/api/business-testing/history")
public class RunHistoryController {
    private final RunHistoryService service;

    public RunHistoryController(RunHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public RunHistoryService.History history(@RequestParam String flowId, @RequestParam String testPointId,
                                             @RequestParam(defaultValue = "50") int limit) {
        return service.history(flowId, testPointId, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
