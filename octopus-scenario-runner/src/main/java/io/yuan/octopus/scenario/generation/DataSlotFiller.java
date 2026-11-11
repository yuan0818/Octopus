package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

import io.yuan.octopus.scenario.extension.FunctionCatalogService;
import io.yuan.octopus.scenario.extension.FunctionToolLoop;
import io.yuan.octopus.scenario.testing.FlowFailure;
import io.yuan.octopus.scenario.testing.FlowModelClient;
import io.yuan.octopus.scenario.testing.IrValidator;
import java.math.BigDecimal;
import java.util.*;

/**
 * Operator 的数据位填充（ADR-CG-03）：先确定性选值；仍有空位时调用模型，模型只能通过函数目录试算并返回绑定。
 * 模型返回的绑定只允许 literal 或已声明的 context，不允许 secret／environment，也不能覆盖敏感参数（OPER-002、OPER-005）。
 */
public class DataSlotFiller {
    public static final String NAMESPACE_VARIABLE = "run.namespace";
    private final FlowModelClient models;
    private final FunctionCatalogService catalog;

    public DataSlotFiller(FlowModelClient models, FunctionCatalogService catalog) {
        this.models = models;
        this.catalog = catalog;
    }

    public record Fill(Map<String, Map<String, Binding>> bindings, List<String> issues, int modelCalls, String promptVersion) {}

    public Fill fill(TestIntent intent, Flow flow, Map<String, Operation> operations, String model) { return fill(intent, flow, operations, model, null); }

    /** deadline：本次填充（含工具轮次与格式修正）可用的总时限；为 null 时只受单次调用上限约束。 */
    public Fill fill(TestIntent intent, Flow flow, Map<String, Operation> operations, String model, io.yuan.octopus.scenario.testing.ModelDeadline deadline) {
        Map<String, Map<String, Binding>> result = new TreeMap<>();
        List<String> issues = new ArrayList<>();
        Map<String, Map<String, Slot>> open = new TreeMap<>();
        for (var call : intent.slots().entrySet()) for (var slot : call.getValue().entrySet()) {
            var binding = deterministic(slot.getValue(), flow);
            if (binding != null) result.computeIfAbsent(call.getKey(), k -> new TreeMap<>()).put(slot.getKey(), binding);
            else open.computeIfAbsent(call.getKey(), k -> new TreeMap<>()).put(slot.getKey(), slot.getValue());
        }
        if (open.isEmpty()) return new Fill(result, issues, 0, null);
        if (models == null) {
            open.forEach((c, inputs) -> inputs.keySet().forEach(i -> issues.add("数据位需要人工或模型填充：" + c + "." + i)));
            return new Fill(result, issues, 0, null);
        }
        var context = new LinkedHashMap<String, Object>();
        context.put("intent", Map.of("intentId", intent.intentId(), "title", intent.title(), "category", intent.category()));
        context.put("slots", open);
        var variables = new TreeMap<String, Object>();
        flow.initialContext().forEach((k, v) -> { if (!v.sensitive()) variables.put(k, v.type()); });
        context.put("availableContext", variables);
        var ports = new TreeMap<String, Object>();
        open.keySet().forEach(callId -> flow.nodes().stream().filter(n -> n.id().equals(callId)).findFirst().ifPresent(n -> {
            var op = operations.get(n.operationRef());
            var types = new TreeMap<String, String>();
            open.get(callId).keySet().forEach(i -> { var port = op.inputs().get(i); types.put(i, port == null ? "unknown" : port.type()); });
            ports.put(callId, types);
        }));
        context.put("ports", ports);
        var loop = catalog == null ? null : new FunctionToolLoop(catalog);
        if (loop != null) context.put("functionTools", FunctionToolLoop.definitions());
        int formatCorrections = 0, calls = 0;
        String version = null;
        while (true) {
            calls++;
            var response = models.caseMaterialize(model, new LinkedHashMap<>(context),
                    deadline == null ? FlowModelClient.CASE_CALL : deadline.next(FlowModelClient.CASE_CALL));
            version = response.promptVersion();
            var toolCalls = loop == null ? null : FunctionToolLoop.toolCalls(response.content());
            if (toolCalls != null) {
                for (var call : toolCalls) if (!TOOLS.contains(String.valueOf(call.get("name")))) issues.add("数据位：模型调用了未登记的工具 " + truncate(String.valueOf(call.get("name"))));
                if (loop.exhausted()) {
                    context.put("toolFeedback", "工具调用已达上限，请直接返回 bindings");
                    // OPER-005：超出工具预算的候选判为静态无效，而不是让整个任务失败
                    if (formatCorrections++ >= 2) { issues.add("数据位：工具调用超出预算"); return new Fill(result, issues, calls, version); }
                    continue;
                }
                loop.execute(toolCalls);
                context.put("toolResults", loop.results());
                continue;
            }
            try {
                var output = ModelIntentSource.parseJson(response.content(), MaterializeOutput.class);
                var accepted = validate(output, open, flow, operations, issues);
                accepted.forEach((c, inputs) -> result.computeIfAbsent(c, k -> new TreeMap<>()).putAll(inputs));
                return new Fill(result, issues, calls, version);
            } catch (FlowFailure failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                // 结构问题（包括嵌套 null）一律按格式错误走有界修正；超过修正次数时只让该候选静态无效，不让整个任务失败
                models.recordFormatInvalid("CASE_MATERIALIZE", model, version);
                if (formatCorrections++ >= 2) { issues.add("数据位：模型返回的取值格式无效"); return new Fill(result, issues, calls, version); }
                String message = invalid instanceof IllegalArgumentException && invalid.getMessage() != null && invalid.getMessage().length() <= 200
                        ? invalid.getMessage() : "输出结构不符合约定（例如出现了 null 或类型错位）";
                context.put("formatFeedback", Map.of("attempt", formatCorrections, "message", message));
            }
        }
    }

    static final Set<String> TOOLS = Set.of("search_functions", "describe_function", "try_function");

    private static String truncate(String value) { return value.length() <= 60 ? value : value.substring(0, 60) + "…"; }

    public record MaterializeOutput(Map<String, Map<String, Binding>> bindings) {}

    static Map<String, Map<String, Binding>> validate(MaterializeOutput output, Map<String, Map<String, Slot>> open, Flow flow,
                                                      Map<String, Operation> operations, List<String> issues) {
        Map<String, Map<String, Binding>> accepted = new TreeMap<>();
        var proposed = output == null || output.bindings() == null ? Map.<String, Map<String, Binding>>of() : output.bindings();
        for (var call : open.entrySet()) for (var input : call.getValue().keySet()) {
            var row = proposed.get(call.getKey());   // 键存在但值为 null 时 getOrDefault 也会返回 null
            var binding = row == null ? null : row.get(input);
            String where = call.getKey() + "." + input;
            if (binding == null) { issues.add("模型未填充数据位：" + where); continue; }
            var node = flow.nodes().stream().filter(n -> n.id().equals(call.getKey())).findFirst().orElse(null);
            var port = node == null ? null : operations.get(node.operationRef()).inputs().get(input);
            if (port == null) { issues.add("数据位引用了不存在的输入：" + where); continue; }
            if (ModelIntentSource.sensitive(input) || ModelIntentSource.sensitive(port.name())) { issues.add("不能以模型取值覆盖敏感参数：" + where); continue; }
            switch (binding.source() == null ? "" : binding.source()) {
                case "literal" -> {
                    if (!IrValidator.matches(binding.value(), port.type())) { issues.add("数据位：模型取值类型不符：" + where); continue; }
                    // VALID 必须满足契约约束，VIOLATING 必须确实违反；只检查类型会让 0 通过 minimum=1 的数据位
                    var slot = call.getValue().get(input);
                    var check = SlotConstraints.check(binding.value(), slot.constraints(), slot.purpose());
                    if (!check.ok()) { issues.add("数据位：模型取值" + check.problem() + "：" + where); continue; }
                    if (check.unverified()) issues.add("模型取值需人工确认（" + check.problem() + "）：" + where);
                    accepted.computeIfAbsent(call.getKey(), k -> new TreeMap<>()).put(input, binding);
                }
                case "context" -> {
                    var variable = flow.initialContext().get(String.valueOf(binding.value()));
                    if (variable == null || variable.sensitive() || !(variable.type().equals(port.type()) || "number".equals(port.type()) && "integer".equals(variable.type()))) {
                        issues.add("模型引用的上下文不可用或类型不符：" + where); continue;
                    }
                    accepted.computeIfAbsent(call.getKey(), k -> new TreeMap<>()).put(input, binding);
                }
                default -> issues.add("模型绑定只能使用 literal 或 context：" + where);
            }
        }
        for (var entry : proposed.entrySet()) {
            if (entry.getValue() == null) { if (!open.containsKey(entry.getKey())) issues.add("模型返回了无效的绑定，已忽略：" + entry.getKey()); continue; }
            for (var input : entry.getValue().keySet())
                if (!open.getOrDefault(entry.getKey(), Map.of()).containsKey(input)) issues.add("模型试图修改数据位以外的输入，已忽略：" + entry.getKey() + "." + input);
        }
        return accepted;
    }

    /** 在约束内确定性选值；做不到（格式、模式、组合约束）时返回 null 交给模型。 */
    static Binding deterministic(Slot slot, Flow flow) {
        if (slot.uniqueKey()) {
            var variable = flow.initialContext().get(NAMESPACE_VARIABLE);
            return variable != null && "string".equals(variable.type()) && "string".equals(slot.type()) ? new Binding("context", NAMESPACE_VARIABLE) : null;
        }
        var c = slot.constraints() == null ? Map.<String, Object>of() : slot.constraints();
        if (c.keySet().stream().anyMatch(k -> Set.of("pattern", "format", "allOf", "anyOf", "oneOf", "not", "$ref").contains(k))) return null;
        if (!"VALID".equals(slot.purpose())) return null;
        if (c.containsKey("const")) return literal(c.get("const"), slot.type());
        if (c.get("enum") instanceof List<?> values && !values.isEmpty()) return literal(values.getFirst(), slot.type());
        switch (slot.type() == null ? "" : slot.type()) {
            case "integer", "number" -> {
                BigDecimal value;
                if (c.get("minimum") instanceof Number min) value = new BigDecimal(min.toString());
                else if (c.get("maximum") instanceof Number max) value = new BigDecimal(max.toString());
                else value = BigDecimal.ONE;
                if (c.get("multipleOf") instanceof Number step) {
                    var s = new BigDecimal(step.toString());
                    if (s.signum() <= 0) return null;
                    var remainder = value.remainder(s);
                    if (remainder.signum() != 0) value = value.subtract(remainder).add(s);
                }
                var binding = new Binding("literal", value);
                return RuleIntentSource.validScalar(value, c) ? binding : null;
            }
            case "string" -> {
                int min = c.get("minLength") instanceof Number n ? n.intValue() : 1;
                if (min > 8000) return null;
                String value = "x".repeat(Math.max(1, min));
                return RuleIntentSource.validScalar(value, c) ? new Binding("literal", value) : null;
            }
            case "boolean" -> { return new Binding("literal", true); }
            default -> { return null; }
        }
    }

    private static Binding literal(Object value, String type) {
        return IrValidator.matches(value, type) ? new Binding("literal", value) : null;
    }
}
