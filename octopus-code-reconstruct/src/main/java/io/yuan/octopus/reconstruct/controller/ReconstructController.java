package io.yuan.octopus.reconstruct.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.reconstruct.dto.ReconstructRequest;
import io.yuan.octopus.reconstruct.dto.ReconstructResponse;
import io.yuan.octopus.reconstruct.service.ReconstructService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class ReconstructController {

    private final ReconstructService reconstructService;

    public ReconstructController(ReconstructService reconstructService) {
        this.reconstructService = reconstructService;
    }

    @PostMapping("/reconstruct")
    public Result<ReconstructResponse> reconstruct(@Valid @RequestBody ReconstructRequest request) {
        try {
            ReconstructResponse response = reconstructService.reconstruct(request);
            return Result.success(response);
        } catch (NoSuchElementException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
