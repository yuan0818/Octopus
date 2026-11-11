package io.yuan.octopus.review.controller;
import io.yuan.octopus.review.service.BusinessFlowSuggestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/api/business-flow")
public class BusinessFlowSuggestionController {
    private final BusinessFlowSuggestionService service;
    public BusinessFlowSuggestionController(BusinessFlowSuggestionService service) { this.service = service; }
    @GetMapping("/models") public List<BusinessFlowSuggestionService.Model> models() { return service.models(); }
    @PostMapping("/suggest") public BusinessFlowSuggestionService.Suggestion suggest(@RequestBody BusinessFlowSuggestionService.Request request) { return service.suggest(request); }
    @PostMapping("/verification") public BusinessFlowSuggestionService.Suggestion verification(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.verification(request); }
    @PostMapping("/step-suggest") public BusinessFlowSuggestionService.Suggestion step(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.step(request); }
    @PostMapping("/plan-ordered") public BusinessFlowSuggestionService.Suggestion plan(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.planOrdered(request); }
    @PostMapping("/repository-background") public BusinessFlowSuggestionService.Suggestion repositoryBackground(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.repositoryBackground(request); }
    @PostMapping("/repository-chat") public BusinessFlowSuggestionService.Suggestion repositoryChat(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.repositoryChat(request); }
    @PostMapping("/negative-cases") public BusinessFlowSuggestionService.Suggestion negativeCases(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.negativeCases(request); }
    @PostMapping("/case-plan") public BusinessFlowSuggestionService.VersionedSuggestion casePlan(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.casePlan(request); }
    @PostMapping("/case-materialize") public BusinessFlowSuggestionService.VersionedSuggestion caseMaterialize(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.caseMaterialize(request); }
    @PostMapping("/run-triage") public BusinessFlowSuggestionService.Suggestion runTriage(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.runTriage(request); }
    @PostMapping("/repository-stage") public BusinessFlowSuggestionService.Suggestion repositoryStage(@RequestBody BusinessFlowSuggestionService.VerificationRequest request) { return service.repositoryStage(request); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(io.yuan.octopus.review.adapter.ModelCallException.class)
    public ResponseEntity<?> modelFailure(io.yuan.octopus.review.adapter.ModelCallException e) {
        var body=new LinkedHashMap<String,Object>();body.put("code",e.code);body.put("upstreamStatus",e.upstreamStatus);
        body.put("message","模型调用失败，请根据错误类别检查配置或重试");return ResponseEntity.status(503).body(body);
    }
}
