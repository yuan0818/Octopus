package io.yuan.octopus.review.controller.admin;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.dto.RequirementItem;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.skill.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/skills")
public class SkillAdminController {

    private final SkillRegistry skillRegistry;
    private final SkillValidator skillValidator;
    private final SkillExecutor skillExecutor;
    private final SkillPackageService skillPackageService;
    private final ModelRegistry modelRegistry;

    public SkillAdminController(SkillRegistry skillRegistry, SkillValidator skillValidator,
                                 SkillExecutor skillExecutor, SkillPackageService skillPackageService,
                                 ModelRegistry modelRegistry) {
        this.skillRegistry = skillRegistry;
        this.skillValidator = skillValidator;
        this.skillExecutor = skillExecutor;
        this.skillPackageService = skillPackageService;
        this.modelRegistry = modelRegistry;
    }

    @GetMapping
    public Result<List<Skill>> listSkills() {
        return Result.success(skillRegistry.getEnabledSkills());
    }

    @GetMapping("/{id}")
    public Result<Skill> getSkill(@PathVariable String id) {
        try {
            return Result.success(skillRegistry.get(id));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PostMapping
    public Result<Void> registerSkill(@RequestBody Skill skill) {
        skillRegistry.register(skill);
        return Result.success(null);
    }

    @PutMapping("/{id}")
    public Result<Void> updateSkill(@PathVariable String id, @RequestBody Skill skill) {
        skillRegistry.register(skill);
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteSkill(@PathVariable String id) {
        skillRegistry.remove(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/toggle")
    public Result<Void> toggleSkill(@PathVariable String id) {
        skillRegistry.toggle(id);
        return Result.success(null);
    }

    /**
     * Validate a skill's configuration without executing it.
     */
    @PostMapping("/{id}/validate")
    public Result<SkillValidator.ValidationResult> validateSkill(@PathVariable String id) {
        try {
            Skill skill = skillRegistry.get(id);
            return Result.success(skillValidator.validate(skill));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    /**
     * Generate the execution plan for a skill (dry run, no LLM call).
     * Returns what will happen when this skill executes.
     */
    @PostMapping("/{id}/execution-plan")
    public Result<SkillExecutionPlan> executionPlan(@PathVariable String id, @RequestBody Map<String, Object> context) {
        try {
            Skill skill = skillRegistry.get(id);
            SkillContext ctx = buildContext(context);
            return Result.success(skillExecutor.plan(skill, ctx));
        } catch (Exception e) {
            return Result.error(500, "Failed to generate execution plan: " + e.getMessage());
        }
    }

    /**
     * Test a skill with sample data (calls LLM but does not store results).
     */
    @PostMapping("/{id}/test")
    public Result<SkillResult> testSkill(@PathVariable String id, @RequestBody Map<String, Object> context) {
        try {
            Skill skill = skillRegistry.get(id);
            SkillContext ctx = buildContext(context);
            return Result.success(skillExecutor.execute(skill, ctx));
        } catch (Exception e) {
            return Result.error(500, "Skill test failed: " + e.getMessage());
        }
    }

    /**
     * Import a skill from a ZIP package upload.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<SkillPackageService.ImportResult> importSkill(@RequestParam("file") MultipartFile file) {
        try {
            SkillPackageService.ImportResult result = skillPackageService.importBytes(file.getBytes());
            if (result.success()) {
                return Result.success(result);
            }
            return Result.error(400, result.message());
        } catch (IOException e) {
            return Result.error(500, "Failed to read upload: " + e.getMessage());
        }
    }

    /**
     * Export a skill as a downloadable ZIP package.
     */
    @GetMapping(value = "/{id}/export", produces = "application/zip")
    public byte[] exportSkill(@PathVariable String id) {
        return skillPackageService.exportZip(id);
    }

    private SkillContext buildContext(Map<String, Object> ctx) {
        String projectId = String.valueOf(ctx.getOrDefault("projectId", "1"));
        String className = (String) ctx.getOrDefault("className", "com.example.Test");
        String methodName = (String) ctx.getOrDefault("methodName", "test");
        String strategy = (String) ctx.getOrDefault("strategy", "BFS");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> reqs = (List<Map<String, String>>) ctx.getOrDefault("requirements", List.of());
        List<RequirementItem> requirements = reqs.stream()
                .map(r -> new RequirementItem(r.get("id"), r.get("title"), r.get("description")))
                .toList();

        @SuppressWarnings("unchecked")
        Map<String, String> sourceCode = (Map<String, String>) ctx.getOrDefault("sourceCode",
                Map.of("com.example.Test", "package com.example;\npublic class Test {}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> chainMetadata = (Map<String, Object>) ctx.getOrDefault("chainMetadata",
                new LinkedHashMap<>(Map.of("anchor", className + "." + methodName, "totalSignatures", 1)));

        return new SkillContext(
                projectId, className, methodName, strategy,
                requirements, "", chainMetadata, sourceCode, null);
    }
}
