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

    public ModelAdminController(ModelRegistry modelRegistry, ModelTester modelTester) {
        this.modelRegistry = modelRegistry;
        this.modelTester = modelTester;
    }

    @GetMapping
    public Result<Set<String>> listModels() {
        return Result.success(modelRegistry.getAvailableModels());
    }

    @GetMapping("/configs")
    public Result<Map<String, ModelConfig>> getConfigs() {
        return Result.success(modelRegistry.getAllConfigs());
    }

    @PostMapping
    public Result<Void> registerModel(@RequestBody ModelConfig config) {
        modelRegistry.register(config);
        return Result.success(null);
    }

    @PutMapping("/{name}")
    public Result<Void> updateModel(@PathVariable String name, @RequestBody ModelConfig config) {
        config.setName(name);
        modelRegistry.register(config);
        return Result.success(null);
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
