package io.yuan.octopus.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.service.BusinessTestImpactService;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/query/business-test-impact")
public class BusinessTestImpactController {
    private final BusinessTestImpactService service;

    public BusinessTestImpactController(BusinessTestImpactService service) {
        this.service = service;
    }

    @PostMapping
    public Result<BusinessTestImpactService.Report> analyze(
            @RequestBody BusinessTestImpactService.Request request) {
        return Result.success(service.analyze(request));
    }
}
