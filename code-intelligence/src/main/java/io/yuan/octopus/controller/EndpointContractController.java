package io.yuan.octopus.controller;
import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.service.EndpointContractService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/query/endpoint-contract")
public class EndpointContractController {
    private final EndpointContractService service;
    public EndpointContractController(EndpointContractService service) { this.service = service; }
    @GetMapping("/source-context") public Result<EndpointContractService.SourceContext> sourceContext(@RequestParam long projectId, @RequestParam String branch,
            @RequestParam String commitId, @RequestParam String signature, @RequestParam String httpMethod, @RequestParam String path) {
        return Result.success(service.sourceContext(projectId, branch, commitId, signature, httpMethod, path));
    }
    @GetMapping public Result<EndpointContractService.Contract> contract(@RequestParam long projectId, @RequestParam String branch,
            @RequestParam String commitId, @RequestParam String signature, @RequestParam String httpMethod, @RequestParam String path) {
        return Result.success(service.contract(projectId, branch, commitId, signature, httpMethod, path));
    }
}
