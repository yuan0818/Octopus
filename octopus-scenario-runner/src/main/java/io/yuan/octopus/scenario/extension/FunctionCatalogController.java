package io.yuan.octopus.scenario.extension;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** FR-API-09: unified function catalog; the same endpoints back the LLM tools search/describe/try. */
@RestController
@RequestMapping("/api/business-testing/functions")
public class FunctionCatalogController {
    private final FunctionCatalogService catalog;

    public FunctionCatalogController(FunctionCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<FunctionCatalog.Brief> search(@RequestParam(value = "query", required = false) String query,
                                              @RequestParam(value = "stage", required = false) String stage,
                                              @RequestParam(value = "limit", required = false) Integer limit) {
        return catalog.search(query, stage, limit == null ? 50 : limit);
    }

    @GetMapping("/describe")
    public Map<String, Object> describe(@RequestParam("name") String name) {
        return catalog.describe(name);
    }

    @GetMapping(value = "/docs", produces = "text/markdown;charset=UTF-8")
    public String docs(@RequestParam("name") String name) {
        return catalog.markdown(name);
    }

    /** Definitions of the discovery tools given to models (search_functions, describe_function, try_function). */
    @GetMapping("/llm-tools")
    public List<Map<String, Object>> llmTools() {
        return FunctionCatalog.metaTools();
    }

    public record TryRequest(String name, Map<String, Object> arguments) {}

    @PostMapping("/try")
    public Map<String, Object> tryCall(@RequestBody TryRequest request) {
        return catalog.tryCall(request.name(), request.arguments());
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
