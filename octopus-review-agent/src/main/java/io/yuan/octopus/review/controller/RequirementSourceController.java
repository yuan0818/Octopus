package io.yuan.octopus.review.controller;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.requirements.RequirementSourceAdapter.FetchedTicket;
import io.yuan.octopus.review.entity.RequirementSourceConfig;
import io.yuan.octopus.review.requirements.RequirementSourceService;
import io.yuan.octopus.review.repository.RequirementSourceConfigRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 需求源：拉取工单为需求条目 + 管理端连接配置。
 * 默认内置 Jira；其他来源实现 RequirementSourceAdapter 注册即自动可用。
 */
@RestController
public class RequirementSourceController {

    private final RequirementSourceService sourceService;
    private final RequirementSourceConfigRepository configRepository;

    public RequirementSourceController(RequirementSourceService sourceService,
                                       RequirementSourceConfigRepository configRepository) {
        this.sourceService = sourceService;
        this.configRepository = configRepository;
    }

    /** 已注册的需求源及配置状态（供 Review 页源选择器）。 */
    @GetMapping("/api/requirements/sources")
    public Result<List<Map<String, Object>>> listSources() {
        return Result.success(sourceService.listSources());
    }

    /** 拉取一个工单的需求条目：GET /api/requirements/fetch?source=jira&ticket=PROJ-123。 */
    @GetMapping("/api/requirements/fetch")
    public Result<FetchedTicket> fetch(@RequestParam String source, @RequestParam String ticket) {
        try {
            return Result.success(sourceService.fetch(source, ticket));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    // ── 管理端连接配置 ──

    @GetMapping("/api/admin/requirements/config/{sourceType}")
    public Result<RequirementSourceConfig> getConfig(@PathVariable String sourceType) {
        RequirementSourceConfig config = configRepository.findBySourceType(sourceType).orElseGet(() -> {
            RequirementSourceConfig empty = new RequirementSourceConfig();
            empty.setSourceType(sourceType);
            empty.setEnabled(true);
            return empty;
        });
        // 密钥脱敏：非空时只回传掩码，前端未修改则不提交
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            config.setSecret(config.getSecret().length() > 4
                    ? config.getSecret().substring(0, 4) + "****" : "****");
        }
        return Result.success(config);
    }

    @PutMapping("/api/admin/requirements/config/{sourceType}")
    public Result<RequirementSourceConfig> saveConfig(@PathVariable String sourceType,
                                                      @RequestBody RequirementSourceConfig body) {
        RequirementSourceConfig config = configRepository.findBySourceType(sourceType).orElseGet(() -> {
            RequirementSourceConfig fresh = new RequirementSourceConfig();
            fresh.setSourceType(sourceType);
            return fresh;
        });
        config.setBaseUrl(body.getBaseUrl());
        config.setAccount(body.getAccount());
        // 掩码值表示未修改密钥
        if (body.getSecret() != null && !body.getSecret().isBlank() && !body.getSecret().contains("****")) {
            config.setSecret(body.getSecret());
        }
        if (body.getExtraJson() != null) {
            config.setExtraJson(body.getExtraJson());
        }
        config.setEnabled(body.getEnabled() != null ? body.getEnabled() : Boolean.TRUE);
        return Result.success(configRepository.save(config));
    }

    @PostMapping("/api/admin/requirements/config/{sourceType}/test")
    public Result<String> test(@PathVariable String sourceType) {
        return Result.success(sourceService.test(sourceType));
    }
}
