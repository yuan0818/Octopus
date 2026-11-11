package io.yuan.octopus.scenario.testing;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/business-testing/flow-suggestions/chains")
public class FlowChainController {
    private final FlowChainService service;
    public FlowChainController(FlowChainService service) { this.service=service; }
    /** Phase 1E：记录整链候选被统一采纳（可选注入）。 */
    private SuggestionMetricsService metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMetrics(SuggestionMetricsService metrics) { this.metrics = metrics; }
    @PostMapping public FlowChainService.View create(@RequestBody FlowChainService.Request request) { return service.create(request); }
    @GetMapping public FlowChainService.View latest(@RequestParam String flowId) { return service.latest(flowId); }
    @GetMapping("/recoverable") public java.util.List<FlowChainService.TaskSummary> recent() { return service.recent(); }
    @GetMapping("/tasks") public java.util.List<FlowChainService.TaskSummary> tasks() { return service.tasks(); }
    @GetMapping("/{id}") public FlowChainService.View get(@PathVariable String id) { return service.get(id); }
    @PostMapping("/{id}/pause") public FlowChainService.View pause(@PathVariable String id) { return service.pause(id); }
    @PostMapping("/{id}/resume") public FlowChainService.View resume(@PathVariable String id) { return service.resume(id); }
    @PostMapping("/{id}/retry") public FlowChainService.View retry(@PathVariable String id,@RequestBody FlowChainService.Retry request) { return service.retry(id,request.nodeId()); }
    @PostMapping("/{id}/cancel") public FlowChainService.View cancel(@PathVariable String id) { return service.cancel(id); }
    @PostMapping("/{id}/confirm") public FlowChainService.View confirm(@PathVariable String id,@RequestBody FlowChainService.Confirm request) {
        var view = service.confirm(id,request);
        if (metrics != null) metrics.record("CHAIN", "ACCEPTED", view.model(), view.id(), null, null, view.nodes() == null ? null : view.nodes().size(), null);
        return view;
    }
}
