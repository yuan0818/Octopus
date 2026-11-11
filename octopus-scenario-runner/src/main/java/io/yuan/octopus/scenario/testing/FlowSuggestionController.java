package io.yuan.octopus.scenario.testing;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/business-testing/flow-suggestions")
public class FlowSuggestionController {
    private final FlowSuggestionService service;
    private final FlowModelClient models;
    private final FlowContractService contracts;
    private final StepSuggestionService steps;
    public FlowSuggestionController(FlowSuggestionService service, FlowModelClient models, FlowContractService contracts, StepSuggestionService steps) { this.service = service; this.models = models; this.contracts = contracts; this.steps = steps; }
    /** Phase 1E：记录人工采纳（可选注入）。 */
    private SuggestionMetricsService metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMetrics(SuggestionMetricsService metrics) { this.metrics = metrics; }
    @GetMapping("/models") public List<FlowModelClient.Model> models() { return models.models(); }
    @PostMapping("/prepare") public FlowContractService.Prepared prepare(@RequestBody TestingModel.Flow flow) { return contracts.prepare(flow); }
    @PostMapping public FlowSuggestionService.Suggestion suggest(@RequestBody FlowSuggestionService.Request request) { return service.suggest(request); }
    @PostMapping("/steps/prepare") public StepSuggestionService.Prepared prepareStep(@RequestBody StepSuggestionService.Prepare request) { return steps.prepare(request); }
    @PostMapping("/steps") public StepSuggestionService.Suggestion suggestStep(@RequestBody StepSuggestionService.Request request) { return steps.suggest(request); }
    @PostMapping("/steps/confirm") public StepMemoryService.View confirmStep(@RequestBody StepSuggestionService.Confirmation request) {
        var view = steps.confirm(request);
        if (metrics != null) metrics.record("STEP", "ACCEPTED", view.model(), view.id(), null, null, null, null);
        return view;
    }
}
