package io.yuan.octopus.review.controller.admin;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.model.ModelConfig;
import io.yuan.octopus.review.model.ModelRegistry;
import io.yuan.octopus.review.model.ModelTester;
import io.yuan.octopus.review.model.ModelTester.ModelTestResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/models")
public class ModelAdminController {

    private final ModelRegistry modelRegistry;
    private final ModelTester modelTester;
    private final io.yuan.octopus.review.service.SecretCipher secretCipher;

    public ModelAdminController(ModelRegistry modelRegistry, ModelTester modelTester,
                                io.yuan.octopus.review.service.SecretCipher secretCipher) {
        this.modelRegistry = modelRegistry;
        this.modelTester = modelTester;
        this.secretCipher = secretCipher;
    }

    @GetMapping
    public Result<Set<String>> listModels() {
        return Result.success(modelRegistry.getAvailableModels());
    }

    @GetMapping("/configs")
    public Result<Map<String, ModelConfig>> getConfigs() {
        Map<String, ModelConfig> configs = modelRegistry.getAllConfigs();
        configs.values().forEach(c -> c.setApiKey(secretCipher.mask(c.getApiKey())));
        return Result.success(configs);
    }

    @PostMapping
    public Result<Void> registerModel(@RequestBody ModelConfig config) {
        String invalid = normalizeStructuredOutput(config);
        if (invalid != null) return Result.error(400, invalid);
        preserveExistingKeyIfUnchanged(null, config);
        modelRegistry.register(config);
        return Result.success(null);
    }

    @PutMapping("/{name}")
    public Result<Void> updateModel(@PathVariable String name, @RequestBody ModelConfig config) {
        config.setName(name);
        String invalid = normalizeStructuredOutput(config);
        if (invalid != null) return Result.error(400, invalid);
        preserveExistingKeyIfUnchanged(name, config);
        modelRegistry.register(config);
        return Result.success(null);
    }

    /** SO-002：规范结构化输出取值；不合法时返回说明（Anthropic 接口不能选 JSON 模式）。 */
    static String normalizeStructuredOutput(ModelConfig config) {
        try {
            config.setStructuredOutput(io.yuan.octopus.review.model.StructuredOutputMode.normalize(
                    config.getApiFormat(), config.getProvider(), config.getStructuredOutput()));
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** 编辑时密钥留空或保持掩码 → 沿用已存密钥（前端永不回显明文）。 */
    private void preserveExistingKeyIfUnchanged(String name, ModelConfig config) {
        if (config.getApiKey() != null && !config.getApiKey().isBlank() && !config.getApiKey().contains("****")) {
            return;
        }
        ModelConfig existing = name != null ? modelRegistry.get(name) : null;
        if (existing == null) {
            // 新建但没填密钥：尝试按 provider 复用已存密钥
            existing = modelRegistry.getAllConfigs().values().stream()
                    .filter(c -> c.getProvider().equals(config.getProvider()))
                    .findFirst().orElse(null);
        }
        if (existing != null) {
            config.setApiKey(existing.getApiKey());
        }
    }

    @DeleteMapping("/{name}")
    public Result<Void> removeModel(@PathVariable String name) {
        modelRegistry.remove(name);
        return Result.success(null);
    }

    @PostMapping("/{name}/toggle")
    public Result<Void> toggleModel(@PathVariable String name) {
        modelRegistry.toggle(name);
        return Result.success(null);
    }

    /**
     * Test model connectivity by sending a minimal prompt.
     */
    @PostMapping("/{name}/test")
    public Result<ModelTestResult> testModel(@PathVariable String name) {
        ModelConfig config = modelRegistry.get(name);
        if (config == null) {
            return Result.error(404, "Model not found: " + name);
        }
        return Result.success(modelTester.test(config));
    }
}
