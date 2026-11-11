package io.yuan.octopus.scenario.testing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {ProjectContextController.class, FlowSuggestionController.class, FlowChainController.class, VerificationController.class, OrderedFlowPlanningController.class, ApiDocumentationController.class, CaseCandidateController.class})
public class ContextApiErrors {
    @ExceptionHandler(FlowFailure.class) public ResponseEntity<?> detail(FlowFailure e) { return ResponseEntity.status(503).body(e.detail("REQUEST",null)); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<?> storage() { return detail(new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE)); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(OptimisticLockingFailureException.class) public ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("message", "配置已更新，请刷新后重试")); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure() { return ResponseEntity.status(500).body(Map.of("message", "服务暂时无法完成请求，请稍后重试")); }
}
