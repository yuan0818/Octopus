package io.yuan.octopus.scenario.testing;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/business-testing/ordered-flows")
public class OrderedFlowPlanningController {
    private final OrderedFlowPlanningService service;
    public OrderedFlowPlanningController(OrderedFlowPlanningService service) {this.service=service;}
    @PostMapping("/plan") public OrderedFlowPlanningService.Candidate plan(@RequestBody OrderedFlowPlanningService.Request request) { return service.generate(request); }
}
