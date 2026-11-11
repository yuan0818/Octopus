package io.yuan.octopus.scenario.testing;

import io.yuan.octopus.verification.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;

@RestController
@RequestMapping("/api/business-testing/verifications")
public class VerificationController {
    public record Request(VerificationPlan.Source source, String environment, String description,
                          Map<String,String> variables, String model) {}
    public record Draft(VerificationPlan plan, List<String> questions, List<String> assumptions) {}
    public record Suggestion(VerificationPlan plan, String preview, List<String> questions, List<String> assumptions, String model) {}
    public record Preview(VerificationPlan plan, String environment) {}
    private final ProjectContextService contexts;
    private final FlowModelClient model;
    public VerificationController(ProjectContextService contexts, FlowModelClient model) { this.contexts = contexts; this.model = model; }

    @PostMapping("/suggest")
    public Suggestion suggest(@RequestBody Request request) {
        require(request.source() != null && request.description() != null && !request.description().isBlank()
                && request.description().length() <= 4000 && request.variables() != null && request.variables().size() <= 200, "请填写业务验证目标并选择连接");
        request.variables().forEach((name, type) -> {
            IrValidator.identifier(name, "verification variable");
            require(Set.of("string", "integer", "number", "boolean").contains(type), "验证变量只支持普通值");
        });
        var source = request.source();
        var resolved = contexts.get(source.repositoryKey(), source.modulePath(), request.environment());
        var connection = resolved.effective().connections().stream().filter(c -> c.id().equals(source.connectionId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择已保存的连接"));
        require(Set.of("mysql", "postgresql", "redis", "kafka").contains(connection.kind()), "该连接尚不支持数据验证");
        // Only logical names, documented schema and variable declarations leave this service.
        var response = model.verification(request.model(), Map.of("source", source, "description", request.description(),
                "background", Map.of("description", resolved.effective().description(), "businessRules", resolved.effective().businessRules()),
                "connection", Map.of("name", connection.name(), "kind", connection.kind(), "structure", connection.notes()), "variables", request.variables()));
        Draft draft;
        try { draft = read(response.content(), Draft.class); }
        catch (RuntimeException e) { throw new IllegalArgumentException("模型返回的验证格式无效，请重新生成"); }
        require(draft != null && draft.questions() != null && draft.assumptions() != null, "模型缺少验证说明，请重新生成");
        if (draft.plan() == null) {
            require(!draft.questions().isEmpty(), "模型未生成验证，请补充结构与业务信息");
            return new Suggestion(null, "", draft.questions(), draft.assumptions(), response.model());
        }
        require(source.equals(draft.plan().connection()), "模型引用了范围之外的连接，请重新生成");
        VerificationRules.validate(draft.plan());
        require(kind(connection.kind()).equals(draft.plan().kind()), "模型生成的查询与连接类型不符");
        VerificationRules.values(draft.plan()).forEach(v -> require(!"context".equals(v.source()) || request.variables().containsKey(v.value()), "模型引用了不存在的流程变量"));
        return new Suggestion(draft.plan(), preview(draft.plan(), connection.kind()), draft.questions(), draft.assumptions(), response.model());
    }
    @PostMapping("/preview")
    public Map<String,String> preview(@RequestBody Preview request) {
        VerificationRules.validate(request.plan());
        var source = request.plan().connection();
        var connection = contexts.get(source.repositoryKey(), source.modulePath(), request.environment()).effective().connections().stream()
                .filter(c -> c.id().equals(source.connectionId())).findFirst().orElseThrow(() -> new IllegalArgumentException("验证连接不存在"));
        require(kind(connection.kind()).equals(request.plan().kind()), "验证类型与连接不符");
        return Map.of("preview", preview(request.plan(), connection.kind()));
    }
    static String kind(String kind) { return switch (kind) { case "mysql", "postgresql" -> "SQL"; case "redis" -> "REDIS"; case "kafka" -> "KAFKA"; default -> "UNSUPPORTED"; }; }
    static String preview(VerificationPlan p, String kind) {
        return switch (p.kind()) {
            case "SQL" -> VerificationRules.sql(p.sql(), kind);
            case "REDIS" -> p.redis().command() + " " + p.redis().keyPrefix() + "<key>" + ("HGET".equals(p.redis().command()) ? " " + p.redis().field() : "");
            default -> "观察 " + p.kafka().topic() + " 在本步骤之后的新消息 · 按 message key 精确匹配";
        };
    }
}
