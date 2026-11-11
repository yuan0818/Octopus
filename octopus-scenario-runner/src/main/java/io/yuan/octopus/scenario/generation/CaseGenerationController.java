package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.read;

import io.yuan.octopus.scenario.testing.FlowFailure;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 设计第 7 章：三角色用例生成任务与生成探测环境授权。 */
@RestController
@RequestMapping("/api/business-testing/case-generation")
public class CaseGenerationController {
    private final CaseGenerationService tasks;
    private final ProbeEnvironmentService environments;

    public CaseGenerationController(CaseGenerationService tasks, ProbeEnvironmentService environments) {
        this.tasks = tasks;
        this.environments = environments;
    }

    @PostMapping("/tasks")
    public CaseGenerationService.TaskView create(@RequestBody Map<String, Object> body, Principal principal) {
        return tasks.create(read(body, TaskRequest.class), user(principal));
    }

    @GetMapping("/tasks")
    public List<CaseGenerationService.TaskSummary> recent(@RequestParam(required = false) String flowId) { return tasks.recent(flowId); }

    @GetMapping("/tasks/{id}")
    public CaseGenerationService.TaskView get(@PathVariable String id) { return tasks.get(id); }

    @PostMapping("/tasks/{id}/cancel")
    public CaseGenerationService.TaskView cancel(@PathVariable String id) { return tasks.cancel(id); }

    @PostMapping("/tasks/{id}/resume")
    public CaseGenerationService.TaskView resume(@PathVariable String id) { return tasks.resume(id); }

    @GetMapping("/tasks/{id}/intents/{intentId}")
    public CaseGenerationService.IntentDetail intent(@PathVariable String id, @PathVariable String intentId) { return tasks.intent(id, intentId); }

    @PostMapping("/tasks/{id}/apply")
    public CaseGenerationService.ApplyResult apply(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return tasks.apply(id, read(body, CaseGenerationService.ApplyRequest.class));
    }

    public record Access(String user, boolean admin) {}

    @GetMapping("/probe-environments")
    public List<ProbeEnvironmentService.View> environments() { return environments.list(); }

    @GetMapping("/probe-environments/access")
    public Access access(Principal principal) { String user = user(principal); return new Access(user, environments.admin(user)); }

    @PutMapping("/probe-environments/{environment}")
    public ProbeEnvironmentService.View put(@PathVariable String environment, @RequestBody Map<String, Object> body, Principal principal) {
        return environments.put(environment, read(body, ProbeEnvironmentService.Request.class), user(principal));
    }

    public record Revoke(String reason) {}

    @PostMapping("/probe-environments/{environment}/revoke")
    public ProbeEnvironmentService.View revoke(@PathVariable String environment, @RequestBody(required = false) Map<String, Object> body, Principal principal) {
        var request = body == null ? new Revoke(null) : read(body, Revoke.class);
        return environments.revoke(environment, request.reason(), user(principal));
    }

    @GetMapping("/probe-environments/{environment}/history")
    public List<ProbeEnvironmentService.HistoryView> history(@PathVariable String environment) { return environments.history(environment); }

    private static String user(Principal principal) { return principal == null ? "anonymous" : principal.getName(); }

    @ExceptionHandler(ProbeEnvironmentService.Forbidden.class)
    public ResponseEntity<Map<String, Object>> forbidden(ProbeEnvironmentService.Forbidden e) {
        return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage() == null ? "请求无效" : e.getMessage()));
    }

    @ExceptionHandler(FlowFailure.class)
    public ResponseEntity<Map<String, Object>> failure(FlowFailure e) {
        var detail = e.detail("CASE_GENERATION", null);
        var body = new LinkedHashMap<String, Object>();
        body.put("code", detail.code());
        body.put("message", detail.message());
        body.put("suggestion", detail.suggestion());
        return ResponseEntity.status(503).body(body);
    }
}
