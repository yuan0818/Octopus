package io.yuan.octopus.review.controller.admin;

import io.yuan.octopus.infra.common.Result;
import io.yuan.octopus.review.tool.Tool;
import io.yuan.octopus.review.tool.ToolRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/tools")
public class ToolAdminController {

    private final ToolRegistry toolRegistry;

    public ToolAdminController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public Result<List<Tool>> listTools() {
        return Result.success(toolRegistry.getAll());
    }

    @GetMapping("/{id}")
    public Result<Tool> getTool(@PathVariable String id) {
        try {
            return Result.success(toolRegistry.get(id));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PostMapping
    public Result<Void> registerTool(@RequestBody Tool tool) {
        toolRegistry.register(tool);
        return Result.success(null);
    }

    @PutMapping("/{id}")
    public Result<Void> updateTool(@PathVariable String id, @RequestBody Tool tool) {
        tool.setId(id);
        toolRegistry.register(tool);
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteTool(@PathVariable String id) {
        toolRegistry.remove(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/toggle")
    public Result<Void> toggleTool(@PathVariable String id) {
        toolRegistry.toggle(id);
        return Result.success(null);
    }

    /**
     * Test a tool by returning its execution info (metadata only, no real execution).
     */
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testTool(@PathVariable String id) {
        try {
            Tool tool = toolRegistry.get(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", tool.getId());
            result.put("name", tool.getName());
            result.put("description", tool.getDescription());
            result.put("enabled", tool.isEnabled());
            result.put("parametersSchema", tool.getParameters());
            result.put("testResult", "Tool definition is valid");
            return Result.success(result);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
