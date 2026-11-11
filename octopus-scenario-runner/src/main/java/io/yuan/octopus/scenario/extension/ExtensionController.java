package io.yuan.octopus.scenario.extension;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** FR-API-10: JMeter extension upload, automatic validation and publication (no approval step, D-04). */
@RestController
@RequestMapping("/api/business-testing/extensions")
public class ExtensionController {
    private final ExtensionService service;
    private final FunctionCatalogService catalog;

    public ExtensionController(ExtensionService service, FunctionCatalogService catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ExtensionService.Summary upload(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "publish", defaultValue = "false") boolean publish,
                                           Principal principal) throws IOException {
        return service.upload(file.getBytes(), file.getOriginalFilename(), user(principal), publish);
    }

    @GetMapping
    public List<ExtensionService.Summary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ExtensionService.Summary get(@PathVariable String id) {
        return service.summary(id);
    }

    /** Human-readable documentation rendered from the same metadata as the LLM tool definitions. */
    @GetMapping(value = "/{id}/docs", produces = "text/markdown;charset=UTF-8")
    public String docs(@PathVariable String id) {
        var summary = service.summary(id);
        var text = new StringBuilder();
        for (var tool : summary.tools()) text.append(catalog.markdown(tool, summary.issues(), summary.deprecated())).append("\n\n");
        return text.toString();
    }

    @PostMapping("/{id}/publish")
    public ExtensionService.Summary publish(@PathVariable String id, Principal principal) {
        return service.publish(id, user(principal));
    }

    @PostMapping("/{id}/disable")
    public ExtensionService.Summary disable(@PathVariable String id) {
        return service.disable(id);
    }

    @PostMapping("/{id}/enable")
    public ExtensionService.Summary enable(@PathVariable String id) {
        return service.enable(id);
    }

    @PostMapping("/{id}/deprecate")
    public ExtensionService.Summary deprecate(@PathVariable String id, @RequestBody Map<String, String> body) {
        return service.deprecate(id, body.get("note"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "请求无效" : e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(503).body(Map.of("error", e.getMessage() == null ? "服务不可用" : e.getMessage()));
    }

    private static String user(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
