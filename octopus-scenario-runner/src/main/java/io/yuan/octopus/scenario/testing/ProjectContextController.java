package io.yuan.octopus.scenario.testing;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

@RestController
@RequestMapping("/api/business-testing/contexts")
public class ProjectContextController {
    private final ProjectContextService service;
    private final ExecutionDefaultsService defaults;
    public ProjectContextController(ProjectContextService service, ExecutionDefaultsService defaults) {
        this.service = service; this.defaults = defaults;
    }
    @GetMapping public ProjectContextService.Resolved get(@RequestParam String repositoryKey,
            @RequestParam(defaultValue = "") String modulePath, @RequestParam(defaultValue = "test") String environment) {
        return service.get(repositoryKey, modulePath, environment);
    }
    @PutMapping public ProjectContextService.Resolved save(@RequestBody ProjectContextService.Save request) { return service.save(request); }
    @GetMapping("/global") public ProjectContextService.Resolved global(@RequestParam(defaultValue="test") String environment) { return service.global(environment); }
    @PutMapping("/global") public ProjectContextService.Resolved saveGlobal(@RequestBody ProjectContextService.SaveGlobal request) { return service.saveGlobal(request); }
    @GetMapping("/environments") public List<String> environments(@RequestParam String repositoryKey) { return service.environments(repositoryKey); }
    @PostMapping("/execution-defaults") public ExecutionDefaultsService.Defaults defaults(@RequestBody ExecutionDefaultsService.DefaultsRequest request) {
        return defaults.resolve(request);
    }
}
