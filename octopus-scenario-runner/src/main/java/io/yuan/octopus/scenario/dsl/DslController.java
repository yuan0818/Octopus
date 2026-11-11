package io.yuan.octopus.scenario.dsl;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** FR-API-01–03: DSL lint, translation into flow drafts, Flow → DSL rendering, and the editor schema. */
@RestController
@RequestMapping("/api/business-testing/dsl")
public class DslController {
    private final DslService service;

    public DslController(DslService service) {
        this.service = service;
    }

    public record Source(String source) {}

    @PostMapping("/lint")
    public DslService.Lint lint(@RequestBody Source body) {
        return service.lint(body.source());
    }

    @PostMapping("/translate")
    public DslService.Translation translate(@RequestBody DslService.TranslateRequest request) {
        return service.translate(request);
    }

    @GetMapping("/render")
    public Map<String, Object> render(@RequestParam("flowId") String flowId, @RequestParam("revision") int revision) {
        var rendered = service.render(flowId, revision);
        var source = service.source(flowId, revision);
        return Map.of("text", rendered.text == null ? "" : rendered.text, "diagnostics", rendered.diagnostics,
                "sourceHash", source.map(s -> s.sourceHash).orElse(""));
    }

    /** JSON Schema of the YAML structure, for editor validation and completion. */
    @GetMapping(value = "/schema", produces = "application/schema+json")
    public String schema() throws java.io.IOException {
        try (var in = getClass().getResourceAsStream("/testing/dsl-schema.json")) {
            if (in == null) throw new IllegalStateException("DSL schema missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "请求无效" : e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(503).body(Map.of("error", e.getMessage() == null ? "服务不可用" : e.getMessage()));
    }
}
