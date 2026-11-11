package io.yuan.octopus.scenario.controller;

import io.yuan.octopus.scenario.entity.Scenario;
import io.yuan.octopus.scenario.entity.ScenarioApiRef;
import io.yuan.octopus.scenario.repository.EnvProfileRepository;
import io.yuan.octopus.scenario.repository.ScenarioApiRefRepository;
import io.yuan.octopus.scenario.repository.ScenarioRepository;
import io.yuan.octopus.scenario.service.DagValidator;
import io.yuan.octopus.scenario.service.EmbeddedJmeterRunner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioRepository scenarioRepo;
    private final EnvProfileRepository profileRepo;
    private final ScenarioApiRefRepository apiRefRepo;
    private final DagValidator validator;

    private final EmbeddedJmeterRunner jmeterRunner;

    public ScenarioController(ScenarioRepository scenarioRepo, EnvProfileRepository profileRepo,
                              ScenarioApiRefRepository apiRefRepo, DagValidator validator,
                              EmbeddedJmeterRunner jmeterRunner) {
        this.scenarioRepo = scenarioRepo;
        this.profileRepo = profileRepo;
        this.apiRefRepo = apiRefRepo;
        this.validator = validator;
        this.jmeterRunner = jmeterRunner;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long projectId) {
        List<Scenario> scenarios = projectId != null
                ? scenarioRepo.findByProjectIdOrderByUpdatedAtDesc(projectId)
                : scenarioRepo.findAll();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scenario s : scenarios) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("projectId", s.getProjectId());
            row.put("envProfileId", s.getEnvProfileId());
            row.put("currentVersion", s.getCurrentVersion());
            row.put("irStale", s.isIrStale());
            row.put("updatedAt", s.getUpdatedAt());
            out.add(row);
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return scenarioRepo.findById(id).map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("projectId", s.getProjectId());
            row.put("envProfileId", s.getEnvProfileId());
            row.put("dagJson", s.getDagJson());
            row.put("irJson", s.getIrJson());
            row.put("currentVersion", s.getCurrentVersion());
            row.put("irStale", s.isIrStale());
            return ResponseEntity.ok(row);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        String dagJson = body.get("dagJson") != null ? body.get("dagJson").toString() : null;
        List<String> errors = validator.validate(dagJson);
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(Map.of("errors", errors));

        Scenario s;
        if (body.get("id") instanceof Number n) {
            s = scenarioRepo.findById(n.longValue()).orElseThrow();
        } else {
            s = new Scenario();
            s.setCreatedBy((String) body.getOrDefault("createdBy", "manual"));
        }
        s.setName((String) body.get("name"));
        if (body.get("projectId") instanceof Number pn) s.setProjectId(pn.longValue());
        if (body.get("envProfileId") instanceof Number en) s.setEnvProfileId(en.longValue());
        s.setDagJson(dagJson);
        s.setIrStale(true);
        s = scenarioRepo.save(s);
        rebuildApiRefs(s, dagJson);
        return ResponseEntity.ok(Map.of("id", s.getId(), "irStale", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scenarioRepo.deleteById(id);
        apiRefRepo.deleteByScenarioId(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/ir")
    public ResponseEntity<?> saveIr(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Scenario s = scenarioRepo.findById(id).orElseThrow();
        s.setIrJson(body.get("irJson") != null ? body.get("irJson").toString() : null);
        s.setIrStale(false);
        s.setCurrentVersion(s.getCurrentVersion() + 1);
        scenarioRepo.save(s);
        return ResponseEntity.ok(Map.of("version", s.getCurrentVersion()));
    }

    @GetMapping("/{id}/jmx")
    public ResponseEntity<?> previewJmx(@PathVariable Long id) {
        return ResponseEntity.badRequest().body(Map.of("error", "JMX preview removed — using embedded JMeter execution now"));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(@PathVariable Long id,
                                 @RequestParam(defaultValue = "600") long timeoutSeconds) {
        Optional<Scenario> opt = scenarioRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Scenario s = opt.get();
        if (s.getIrJson() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "IR not assembled (irStale=true)"));
        }

        Map<String, Object> profile = buildProfile(s);
        EmbeddedJmeterRunner.RunResult result = jmeterRunner.run(s.getIrJson(), profile, timeoutSeconds * 1000);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (var row : result.steps()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepId", row.label());
            step.put("callLabel", row.label());
            step.put("success", row.success());
            step.put("status", row.responseCode());
            step.put("elapsedMs", row.elapsedMs());
            step.put("responseBody", row.responseData());
            step.put("url", row.url());
            step.put("error", row.success() ? null : (row.failureMessage() != null ? row.failureMessage() : "HTTP " + row.responseCode()));
            steps.add(step);
        }
        return ResponseEntity.ok(Map.of(
                "success", result.success(),
                "steps", steps,
                "totalMs", result.totalMs(),
                "error", result.error() != null ? result.error() : ""
        ));
    }

    @GetMapping("/by-apis")
    public List<Long> scenariosByApis(@RequestParam Long projectId, @RequestParam List<String> apiIdentities) {
        return apiRefRepo.findByProjectIdAndApiIdentityIn(projectId, apiIdentities)
                .stream().map(ScenarioApiRef::getScenarioId).distinct().toList();
    }

    private Map<String, Object> buildProfile(Scenario s) {
        Map<String, Object> profile = new HashMap<>();
        if (s.getEnvProfileId() != null) {
            profileRepo.findById(s.getEnvProfileId()).ifPresent(p -> {
                if (p.getBaseUrl() != null) profile.put("baseUrl", p.getBaseUrl());
                if (p.getConfigJson() != null) {
                    try {
                        var cfg = com.google.gson.JsonParser.parseString(p.getConfigJson()).getAsJsonObject();
                        cfg.entrySet().forEach(e -> profile.put(e.getKey(),
                                e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString()));
                    } catch (Exception ignored) {}
                }
            });
        }
        return profile;
    }

    private void rebuildApiRefs(Scenario scenario, String dagJson) {
        apiRefRepo.deleteByScenarioId(scenario.getId());
        try {
            var dag = com.google.gson.JsonParser.parseString(dagJson).getAsJsonObject();
            var steps = dag.getAsJsonArray("steps");
            Set<String> apis = new LinkedHashSet<>();
            for (var el : steps) {
                var step = el.getAsJsonObject();
                if (step.has("calls") && step.get("calls").isJsonArray()) {
                    for (var c : step.getAsJsonArray("calls")) {
                        if (c.getAsJsonObject().has("api")) apis.add(c.getAsJsonObject().get("api").getAsString());
                    }
                } else if (step.has("api")) {
                    apis.add(step.get("api").getAsString());
                }
            }
            for (String api : apis) {
                ScenarioApiRef ref = new ScenarioApiRef();
                ref.setProjectId(scenario.getProjectId());
                ref.setApiIdentity(api);
                ref.setScenarioId(scenario.getId());
                apiRefRepo.save(ref);
            }
        } catch (Exception ignored) {}
    }
}
