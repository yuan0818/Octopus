package io.yuan.octopus.review.controller.admin;

import io.yuan.octopus.infra.common.CredentialEncryptor;
import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.prompt.PromptManager;
import io.yuan.octopus.review.skill.SkillRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/admin/config")
public class ConfigAdminController {

    private final ModelRegistry modelRegistry;
    private final PromptManager promptManager;
    private final SkillRegistry skillRegistry;

    public ConfigAdminController(ModelRegistry modelRegistry, PromptManager promptManager,
                                  SkillRegistry skillRegistry) {
        this.modelRegistry = modelRegistry;
        this.promptManager = promptManager;
        this.skillRegistry = skillRegistry;
    }

    @GetMapping("/api-keys")
    public Result<Map<String, String>> getApiKeys() {
        // 统一脱敏规则（与模型管理页一致）：最多暴露前 4 个字符
        Map<String, String> masked = new java.util.LinkedHashMap<>();
        modelRegistry.getAllApiKeys().forEach((provider, key) ->
                masked.put(provider, CredentialEncryptor.mask(key)));
        return Result.success(masked);
    }

    @PutMapping("/api-keys/{provider}")
    public Result<Void> updateApiKey(@PathVariable String provider, @RequestBody Map<String, String> body) {
        String apiKey = body.get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            return Result.error(400, "apiKey is required");
        }
        modelRegistry.setApiKey(provider, apiKey);
        return Result.success(null);
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Models
        status.put("models", modelRegistry.getAvailableModels().size());
        status.put("models_detail", modelRegistry.getAllConfigs().entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "provider", e.getValue().getProvider(),
                        "enabled", e.getValue().isEnabled()))
                .toList());

        // API Keys
        Map<String, String> keyStatus = new LinkedHashMap<>();
        modelRegistry.getAllApiKeys().forEach((provider, key) -> {
            keyStatus.put(provider, key != null && !key.isBlank() ? "configured" : "missing");
        });
        status.put("api_keys", keyStatus);

        // Prompts
        status.put("prompts", promptManager.getTemplateIds().size());

        // Skills
        status.put("skills", skillRegistry.getEnabledSkills().size());

        // Readiness
        boolean ready = modelRegistry.getAvailableModels().size() > 0
                && promptManager.getTemplateIds().size() > 0
                && skillRegistry.getEnabledSkills().size() > 0
                && !modelRegistry.getAllApiKeys().isEmpty();
        status.put("ready_for_review", ready);

        return Result.success(status);
    }
}
