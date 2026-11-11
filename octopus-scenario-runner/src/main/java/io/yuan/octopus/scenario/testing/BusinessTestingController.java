package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/business-testing")
public class BusinessTestingController {
    private final BusinessKnowledgeStore store;
    private final ApiKnowledgeImporter importer;
    private final ScenarioPlanner planner;
    private final IrValidator validator;
    private final CapabilityRegistry capabilities;
    private final BusinessTestExecutionService execution;
    private final BusinessTestScenarioRepository scenarios;
    private final BusinessTestRunRepository runs;
    private final BusinessTestImpactBridge impact;
    private final String configuredReviewer;

    public BusinessTestingController(
            BusinessKnowledgeStore store,
            ApiKnowledgeImporter importer,
            ScenarioPlanner planner,
            IrValidator validator,
            CapabilityRegistry capabilities,
            BusinessTestExecutionService execution,
            BusinessTestScenarioRepository scenarios,
            BusinessTestRunRepository runs,
            BusinessTestImpactBridge impact,
            @Value("${scenario.testing.reviewer:}") String reviewer) {
        this.store = store;
        this.importer = importer;
        this.planner = planner;
        this.validator = validator;
        this.capabilities = capabilities;
        this.execution = execution;
        this.scenarios = scenarios;
        this.runs = runs;
        this.impact = impact;
        this.configuredReviewer = reviewer;
    }

    @GetMapping("/preparation-tools")
    public List<io.yuan.octopus.preparation.PreparationTools.Tool> preparationTools() { return io.yuan.octopus.preparation.PreparationTools.ALL; }

    @GetMapping("/capabilities")
    public List<Capability> capabilities() {
        return capabilities.list();
    }

    @GetMapping("/operations")
    public List<Operation> operations() {
        return store.operations();
    }

    @PostMapping("/operations")
    public Operation operation(@RequestBody Map<String, Object> body) {
        Operation operation = read(body, Operation.class);
        validator.validateOperation(operation);
        return store.saveOperation(operation);
    }

    @PostMapping("/operations/import-openapi")
    public List<Operation> openApi(@RequestBody Map<String, Object> body) {
        var imported = importer.openApi(read(body, ApiKnowledgeImporter.ImportRequest.class));
        var existing = store.operations();
        return imported.stream()
                .map(
                        o -> {
                            List<Implementation> mappings = new ArrayList<>(o.implementations());
                            existing.stream()
                                    .filter(
                                            e ->
                                                    e.system().equals(o.system())
                                                            && e.service().equals(o.service())
                                                            && e.method().equals(o.method())
                                                            && e.path().equals(o.path()))
                                    .forEach(
                                            e ->
                                                    e.implementations()
                                                            .forEach(
                                                                    i -> {
                                                                        if (!mappings.contains(i))
                                                                            mappings.add(i);
                                                                    }));
                            return store.saveOperation(
                                    new Operation(
                                            o.operationId(),
                                            o.revision(),
                                            o.system(),
                                            o.service(),
                                            o.method(),
                                            o.path(),
                                            o.inputs(),
                                            o.requestSchema(),
                                            o.responseSchema(),
                                            mappings,
                                            o.source(),
                                            o.requiresCapabilities(),
                                            o.securityProfile()));
                        })
                .toList();
    }

    public record EndpointImport(
            long projectId, String branch, String commitId, String system, String service) {}

    @PostMapping("/operations/import-endpoints")
    public List<Operation> endpoints(@RequestBody Map<String, Object> body) {
        var request = read(body, EndpointImport.class);
        List<Operation> existing = store.operations();
        return impact.endpoints(request.projectId(), request.branch(), request.commitId()).stream()
                .flatMap(
                        e -> {
                            if (!"*".equals(e.get("httpMethod")))
                                return java.util.stream.Stream.of(e);
                            return List.of(
                                            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD",
                                            "OPTIONS")
                                    .stream()
                                    .map(
                                            method -> {
                                                var route = new HashMap<>(e);
                                                route.put("httpMethod", method);
                                                return (Map<String, Object>) route;
                                            });
                        })
                .map(e -> importer.endpoint(request.system(), request.service(), e))
                .flatMap(
                        discovered -> {
                            var contracts =
                                    existing.stream()
                                            .filter(
                                                    o ->
                                                            o.system().equals(discovered.system())
                                                                    && o.service()
                                                                            .equals(
                                                                                    discovered
                                                                                            .service())
                                                                    && o.method()
                                                                            .equals(
                                                                                    discovered
                                                                                            .method())
                                                                    && o.path()
                                                                            .equals(
                                                                                    discovered
                                                                                            .path())
                                                                    && o.requestSchema() != null)
                                            .toList();
                            if (contracts.isEmpty())
                                return java.util.stream.Stream.of(store.saveOperation(discovered));
                            return contracts.stream()
                                    .map(
                                            o ->
                                                    store.saveOperation(
                                                            new Operation(
                                                                    o.operationId(),
                                                                    o.revision(),
                                                                    o.system(),
                                                                    o.service(),
                                                                    o.method(),
                                                                    o.path(),
                                                                    o.inputs(),
                                                                    o.requestSchema(),
                                                                    o.responseSchema(),
                                                                    discovered.implementations(),
                                                                    o.source(),
                                                                    o.requiresCapabilities(),
                                                                    o.securityProfile())));
                        })
                .toList();
    }

    @PostMapping("/operations/refresh-implementation-links")
    public Map<String, Object> refresh() {
        store.refreshImplementationLinks();
        return Map.of("refreshed", true);
    }

    @GetMapping("/flows")
    public List<FlowRevision> flows() {
        return store.flows();
    }

    @GetMapping("/flows/{id}/{revision}")
    public FlowRevision flow(@PathVariable String id, @PathVariable int revision) {
        return store.flow(id, revision);
    }

    @PostMapping("/flows")
    public FlowRevision saveFlow(@RequestBody Map<String, Object> body) {
        var flow = read(body, Flow.class);
        planner.validateDraft(flow);
        return store.saveDraft(flow);
    }

    public record MigrateFlow(Flow flow, String testPointId) {}
    @PostMapping("/flows/migrate-ordered")
    public ScenarioPlanner.Migration migrate(@RequestBody Map<String,Object> body) {
        var request = read(body, MigrateFlow.class);
        return planner.migrate(request.flow(), request.testPointId());
    }

    public record Decision(String expectedHash, boolean accept) {}

    @PostMapping("/flows/{id}/{revision}/decision")
    public FlowRevision decision(
            @PathVariable String id,
            @PathVariable int revision,
            @RequestBody Map<String, Object> body,
            Principal principal) {
        var request = read(body, Decision.class);
        if (request.accept()) planner.validateFlow(store.flow(id, revision).definition());
        return store.decide(
                id,
                revision,
                request.expectedHash(),
                principal == null ? configuredReviewer : principal.getName(),
                request.accept());
    }

    public record Generate(
            String flowId, int flowRevision, String testPointId, List<String> changeEvidenceRefs) {}

    @PostMapping("/scenarios")
    public BusinessTestScenario generate(@RequestBody Map<String, Object> body) {
        var request = read(body, Generate.class);
        return execution.save(
                planner.generate(
                        request.flowId(),
                        request.flowRevision(),
                        request.testPointId(),
                        request.changeEvidenceRefs()));
    }

    @GetMapping("/scenarios")
    public List<BusinessTestScenario> scenarios() {
        return scenarios.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/scenarios/{id}/ir")
    public TestIR ir(@PathVariable String id) {
        return execution.load(id);
    }

    @GetMapping("/scenarios/{id}/evidence")
    public Object evidence(@PathVariable String id) {
        return store.evidenceSnapshots(execution.load(id).changeEvidenceRefs());
    }

    @PostMapping("/scenarios/{id}/compile")
    public Compilation compile(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return execution.compile(id, read(body, ExecutionProfile.class));
    }

    public record RunRequest(ExecutionProfile profile, long timeoutSeconds) {}

    @PostMapping("/scenarios/{id}/runs")
    public ResponseEntity<BusinessTestRun> run(
            @PathVariable String id, @RequestBody Map<String, Object> body,
            @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey) {
        var request = read(body, RunRequest.class);
        return ResponseEntity.accepted()
                .body(execution.run(id, request.profile(), request.timeoutSeconds(),idempotencyKey));
    }

    @GetMapping("/runs")
    public List<BusinessTestRun> runs() {
        return runs.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/runs/{id}")
    public BusinessTestRun run(@PathVariable String id) {
        return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown run"));
    }

    @PostMapping("/runs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        execution.cancel(id);
        return Map.of("cancellationRequested", true);
    }

    @PostMapping("/impact")
    public BusinessTestImpactBridge.Impact impact(@RequestBody Map<String, Object> body) {
        return impact.analyze(body);
    }

    @PostMapping("/ir/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) {
        return Map.of(
                "orderedSteps",
                validator.validate(read(body, TestIR.class)).stream().map(Step::id).toList());
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        com.google.gson.JsonParseException.class,
        NullPointerException.class
    })
    public ResponseEntity<Map<String, String>> invalid(Exception exception) {
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "error",
                                exception instanceof NullPointerException
                                        ? "Incomplete request"
                                        : exception.getMessage() == null
                                                ? "Invalid request"
                                                : exception.getMessage()));
    }

    @ExceptionHandler(RunSubmissionStore.Conflict.class)
    public ResponseEntity<Map<String,String>> submissionConflict(RunSubmissionStore.Conflict exception) {
        return ResponseEntity.status(409).body(Map.of("error",exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException exception) {
        return ResponseEntity.status(503)
                .body(
                        Map.of(
                                "error",
                                exception.getMessage() == null
                                        ? "Dependency unavailable"
                                        : exception.getMessage()));
    }
}
