package io.yuan.octopus.review.controller.admin;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.prompt.PromptManager;
import io.yuan.octopus.review.prompt.PromptTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/prompts")
public class PromptAdminController {

    private final PromptManager promptManager;

    public PromptAdminController(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @GetMapping
    public Result<List<String>> listTemplates() {
        return Result.success(promptManager.getTemplateIds());
    }

    @GetMapping("/{id}")
    public Result<PromptTemplate> getTemplate(@PathVariable String id) {
        try {
            return Result.success(promptManager.get(id));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PostMapping
    public Result<Void> registerTemplate(@RequestBody PromptTemplate template) {
        promptManager.register(template);
        return Result.success(null);
    }

    @PutMapping("/{id}")
    public Result<Void> updateTemplate(@PathVariable String id, @RequestBody PromptTemplate template) {
        promptManager.register(template);
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteTemplate(@PathVariable String id) {
        promptManager.remove(id);
        return Result.success(null);
    }

    /**
     * Preview the rendered prompt with sample variables.
     */
    @PostMapping("/{id}/preview")
    public Result<Map<String, Object>> previewTemplate(@PathVariable String id, @RequestBody(required = false) Map<String, Object> variables) {
        try {
            PromptTemplate template = promptManager.get(id);
            if (variables == null) {
                variables = new LinkedHashMap<>();
                variables.put("anchor", "com.example.Service.process");
                variables.put("projectContext", "★ [ESTABLISHED] 使用 @Slf4j");
                variables.put("requirements", "- REQ-001: 参数校验 — 使用 @Valid");
                variables.put("sourceCode", "package com.example;\n@Service\npublic class Service {\n    public void process() {}\n}");
            }
            String rendered = template.renderUserPrompt(variables);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("templateId", template.id());
            result.put("templateName", template.name());
            result.put("systemPrompt", template.systemPrompt());
            result.put("renderedUserPrompt", rendered);
            result.put("outputSchema", template.outputSchema());
            result.put("charCount", rendered.length());
            result.put("estimatedTokens", rendered.length() / 4);
            return Result.success(result);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
