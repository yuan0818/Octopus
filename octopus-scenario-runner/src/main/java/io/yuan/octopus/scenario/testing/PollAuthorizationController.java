package io.yuan.octopus.scenario.testing;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** FR-API-11: per-API poll authorizations; single-API operations only, no bulk endpoint by design. */
@RestController
@RequestMapping("/api/business-testing/poll-authorizations")
public class PollAuthorizationController {
    private final PollAuthorizationService service;

    public PollAuthorizationController(PollAuthorizationService service) {
        this.service = service;
    }

    @GetMapping
    public List<PollAuthorization> list() {
        return service.list();
    }

    @PostMapping
    public PollAuthorization authorize(@RequestBody PollAuthorizationService.Request request, Principal principal) {
        return service.authorize(request, principal == null ? "anonymous" : principal.getName());
    }

    @PostMapping("/{id}/revoke")
    public PollAuthorization revoke(@PathVariable String id, Principal principal) {
        return service.revoke(id, principal == null ? "anonymous" : principal.getName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "请求无效" : e.getMessage()));
    }
}
