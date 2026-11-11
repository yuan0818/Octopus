package io.yuan.octopus.scenario.testing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static io.yuan.octopus.scenario.testing.TestingJson.read;

/**
 * Phase 2.4：数据驱动执行。
 * {@code POST /api/business-testing/data-runs}：{@code {scenarioId, services, environment?, secretEnvironmentNames?,
 * initialContext?, rows:[{name, initialContext}], timeoutSeconds?}}。
 */
@RestController
@RequestMapping("/api/business-testing/data-runs")
public class DataRunController {
    private final DataRunService service;

    public DataRunController(DataRunService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DataRunService.Summary> start(@RequestBody Map<String, Object> body) {
        return ResponseEntity.accepted().body(service.start(read(body, DataRunService.StartRequest.class)));
    }

    @GetMapping
    public List<DataRunService.Summary> list(@RequestParam String scenarioId) {
        return service.list(scenarioId);
    }

    @GetMapping("/{id}")
    public DataRunService.Summary get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/cancel")
    public DataRunService.Summary cancel(@PathVariable String id) {
        return service.cancel(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
