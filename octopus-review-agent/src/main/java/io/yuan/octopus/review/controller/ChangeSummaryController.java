package io.yuan.octopus.review.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.dto.ChangeSummaryRequest;
import io.yuan.octopus.review.service.ChangeSummaryService;
import io.yuan.octopus.review.service.ChangeSummaryService.ChangeSummaryResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 变更总结：同一入口在两个版本（分支/commit/tag 任意组合）之间的
 * 修改要点 + 测试要点。链路差异 git-backed（entry-chain-diff）。
 */
@RestController
@RequestMapping("/api")
public class ChangeSummaryController {

    private final ChangeSummaryService changeSummaryService;

    public ChangeSummaryController(ChangeSummaryService changeSummaryService) {
        this.changeSummaryService = changeSummaryService;
    }

    @PostMapping("/review/change-summary")
    public Result<ChangeSummaryResult> summarize(@Valid @RequestBody ChangeSummaryRequest request) {
        try {
            return Result.success(changeSummaryService.summarize(request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
