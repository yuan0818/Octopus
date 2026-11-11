package io.yuan.octopus.scenario.testing;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/business-testing/case-candidates")
public class CaseCandidateController {
    private final CaseCandidateService service;
    public CaseCandidateController(CaseCandidateService service) {this.service=service;}
    @PostMapping public CaseCandidateService.Result generate(@RequestBody CaseCandidateService.Request request) {return service.generate(request);}
}
