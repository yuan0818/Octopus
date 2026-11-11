package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;
import java.util.*;

/** An allowlisted patch cannot express a different business path or replace existing business rules. */
final class FlowFillValidator {
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "object", "array");
    static void validateRequest(FlowSuggestionService.Request r) {
        var f = r.flow();
        require(f != null && f.nodes() != null && !f.nodes().isEmpty() && f.nodes().size() <= (f.steps()==null ? 50 : 300),
                "请先选择 API 并编排业务路径，再使用 AI 补全（有序流程最多 100 步、300 次调用）");
        require(f.name() != null && f.initialContext() != null && f.dependencies() != null && f.stateModels() != null
                && f.testPoints() != null && !f.testPoints().isEmpty() && f.testPoints().size() <= 20
                && r.contextValues() != null && r.editableInputs() != null, "补全请求不完整");
        identifier(f.flowId(), "flow");
        require(canonical(r).length() <= 200000, "补全背景过多");
        Map<String, Variable> vars = new HashMap<>(f.initialContext());
        vars.forEach((key, v) -> { identifier(key, "context"); require(v != null && TYPES.contains(v.type()), "初始变量类型无效"); });
        r.contextValues().forEach((key, value) -> {
            var variable = f.initialContext().get(key);
            require(variable != null && !variable.sensitive() && !sensitive(key) && matches(value, variable.type()) && !hasSensitiveField(value),
                    "预置数据只能使用已声明的普通变量，敏感值请在运行时配置");
        });
        Set<String> ids = new HashSet<>();
        for (var n : f.nodes()) {
            identifier(n.id(), "step"); require(ids.add(n.id()) && n.label() != null && n.operationRef() != null
                    && n.inputs() != null && n.outputs() != null && n.states() != null, "步骤定义不完整或重复");
            io.yuan.octopus.preparation.PreparationTools.validate(n.preparation());
            n.outputs().forEach(o -> { identifier(o.key(), "output"); require(TYPES.contains(o.type())
                    && vars.putIfAbsent(o.key(), new Variable(o.type(), o.sensitive())) == null, "上下文生产者重复或类型无效"); });
        }
        r.editableInputs().forEach((id, ports) -> require(ids.contains(id) && ports != null && ports.size() <= 100
                && new HashSet<>(ports).size() == ports.size(), "补全范围包含未知或重复的步骤/参数"));
        f.dependencies().forEach(e -> require(ids.contains(e.from()) && ids.contains(e.to()) && !e.from().equals(e.to()), "步骤依赖无效"));
        Set<String> points = new HashSet<>();
        f.testPoints().forEach(p -> {
            identifier(p.id(), "point"); require(points.add(p.id()) && p.covers() != null && ids.containsAll(p.covers())
                    && p.assertions() != null && p.inputOverrides() != null, "测试点无效");
        });
        if(f.steps()!=null) f.testPoints().forEach(p -> OrderedFlows.prefix(f,p));
        var ancestors = ancestors(f);
        ids.forEach(id -> require(!ancestors.get(id).contains(id), "请先修正现有流程的循环依赖"));
    }

    static Flow apply(FlowSuggestionService.Request r, FlowSuggestionService.Patch patch, Map<String, Operation> operations) {
        var f = r.flow();
        require(patch != null && patch.nodes() != null && patch.assertions() != null && patch.assertions().size() <= 200
                && patch.assumptions() != null && patch.questions() != null, "补全内容不完整");
        require(patch.nodes().stream().map(FlowSuggestionService.NodeFill::id).toList().equals(f.nodes().stream().map(FlowNode::id).toList()),
                "AI 不能添加、删除或重排步骤");
        for (var list : List.of(patch.assumptions(), patch.questions())) {
            require(list.size() <= 100, "提示过多"); list.forEach(s -> require(s != null && s.length() <= 4000, "提示无效"));
        }
        Map<String, Variable> variables = new HashMap<>(f.initialContext());
        Map<String, String> producers = new HashMap<>();
        f.nodes().forEach(n -> n.outputs().forEach(o -> { variables.put(o.key(), new Variable(o.type(), o.sensitive())); producers.put(o.key(), n.id()); }));
        // Collect output declarations before validating bindings; list order is presentation, the locked graph defines upstream.
        for (int i = 0; i < f.nodes().size(); i++) {
            var n = f.nodes().get(i); var fill = patch.nodes().get(i); var operation = operations.get(n.operationRef());
            require(fill.inputs() != null && fill.outputs() != null && fill.outputs().size() <= 100, "补全步骤格式无效");
            for (var o : fill.outputs()) {
                identifier(o.key(), "output"); require(TYPES.contains(o.type()), "输出类型无效");
                require(variables.putIfAbsent(o.key(), new Variable(o.type(), o.sensitive())) == null, "不得覆盖已有上下文或输出");
                require(!"$.status".equals(o.path()), "运行时不提取状态码，状态码请使用 STATUS_EQUALS 检查");
                require(responseTypes(operation, o.path()).contains(o.type()), "提取路径或类型未在响应契约中定义");
                require(!sensitive(o.key() + "." + o.path()) || o.sensitive(), "敏感输出必须标记为敏感");
                producers.put(o.key(), n.id());
            }
        }
        List<FlowNode> nodes = new ArrayList<>();
        var ancestors = ancestors(f);
        for (int i = 0; i < f.nodes().size(); i++) {
            var n = f.nodes().get(i); var fill = patch.nodes().get(i); var operation = operations.get(n.operationRef());
            var inputs = new LinkedHashMap<>(n.inputs());
            fill.inputs().forEach((key, b) -> {
                require(r.editableInputs().getOrDefault(n.id(), List.of()).contains(key), "不得覆盖未指定的输入");
                var port = operation.inputs().get(key); require(port != null && b != null && b.source() != null, "未知输入参数");
                switch (b.source()) {
                    case "literal" -> {
                        require(!sensitive(key) && !sensitive(port.name()) && !hasSensitiveField(b.value()) && matches(b.value(), port.type()), "输入类型无效或包含敏感字面量");
                        if (b.value() instanceof Map || b.value() instanceof List) {
                            Object schema = "body".equals(port.location()) && "$".equals(port.name()) ? operation.requestSchema() :
                                    operation.requestSchema() != null && operation.requestSchema().get("x-octopus-parameters") instanceof Map<?, ?> params ? params.get(key) : null;
                            require(knownValue(b.value(), schema), "结构化输入包含未定义的契约字段");
                        }
                    }
                    case "context" -> {
                        String name = Objects.toString(b.value(), ""); var v = variables.get(name);
                        String issue = v == null ? "CONTEXT_UNDECLARED" :
                                producers.containsKey(name) && !ancestors.get(n.id()).contains(producers.get(name)) ? "CONTEXT_NOT_UPSTREAM" :
                                !FlowBindingException.compatible(v.type(), port.type()) ? "CONTEXT_TYPE_MISMATCH" : null;
                        if (issue != null) {
                            var available = new TreeMap<String, Variable>();
                            variables.forEach((k, variable) -> { if (!producers.containsKey(k) || ancestors.get(n.id()).contains(producers.get(k))) available.put(k, variable); });
                            throw new FlowBindingException(issue, key, port.type(), v == null ? null : v.type(),
                                    available.containsKey(name) ? name : null, FlowBindingException.matching(available, port.type()));
                        }
                    }
                    case "secret" -> {
                        identifier(Objects.toString(b.value(), ""), "secret");
                        require(f.nodes().stream().flatMap(node -> node.inputs().values().stream()).anyMatch(old ->
                                "secret".equals(old.source()) && Objects.equals(old.value(), b.value())), "未知密钥引用，请先由用户配置");
                    }
                    default -> throw new IllegalArgumentException("不支持的绑定来源");
                }
                inputs.put(key, b);
            });
            var outputs = new ArrayList<>(n.outputs()); outputs.addAll(fill.outputs());
            var preparation=n.preparation();
            if(fill.preparation()!=null) {
                require(preparation==null || canonical(preparation).equals(canonical(fill.preparation())),"不得覆盖已有请求预处理计划，请先手动移除或修改");
                var deps=new HashSet<String>();
                PreparationChecks.validate(fill.preparation(),operation,inputs,variables,producers,deps,FlowBindingException.available(f,n.id()));
                require(ancestors.get(n.id()).containsAll(deps),"预处理只能使用既定路径上游输出");
                preparation=fill.preparation();
            }
            nodes.add(new FlowNode(n.id(), n.label(), n.operationRef(), inputs, outputs, n.states(), n.waitMillis(),preparation,n.poll()));
        }
        Map<String, Map<String, List<Assertion>>> additions = new HashMap<>();
        for (var fill : patch.assertions()) {
            var point = f.testPoints().stream().filter(p -> p.id().equals(fill.pointId())).findFirst().orElseThrow();
            require(point.covers().contains(fill.nodeId()), "断言步骤不属于该测试点");
            var node = f.nodes().stream().filter(n -> n.id().equals(fill.nodeId())).findFirst().orElseThrow();
            var a = fill.assertion(); require(a != null, "断言不能为空"); identifier(a.id(), "assertion");
            var checks = additions.computeIfAbsent(point.id(), key -> new HashMap<>()).computeIfAbsent(node.id(), key -> new ArrayList<>(point.assertions().getOrDefault(key, List.of())));
            require(checks.stream().noneMatch(old -> old.id().equals(a.id())), "不得覆盖已有断言");
            var operation = operations.get(node.operationRef());
            if ("STATUS_EQUALS".equals(a.kind())) {
                require(checks.stream().noneMatch(old -> "STATUS_EQUALS".equals(old.kind())) && a.expected() instanceof Number
                        && matches(a.expected(), "integer") && ((Number) a.expected()).intValue() >= 100 && ((Number) a.expected()).intValue() <= 599 && operation.responseSchema() != null
                        && operation.responseSchema().containsKey(String.valueOf(((Number) a.expected()).intValue())), "状态码未在契约中定义或已有状态码断言");
            } else if (ResponseSchemaChecks.KIND.equals(a.kind())) {
                // 模型只能整体引用契约 Schema，不能自拟 Schema
                require("$".equals(a.path()) && a.expected() == null && operation.responseSchema() != null && !operation.responseSchema().isEmpty(),
                        "契约校验只能使用 path=$、expected=null，且接口需有响应契约");
                require(checks.stream().noneMatch(old -> ResponseSchemaChecks.KIND.equals(old.kind())), "该步骤已有契约校验");
            } else {
                require(!"$.status".equals(a.path()), "状态码请使用 STATUS_EQUALS 检查");
                var types = responseTypes(operation, a.path()); require(!types.isEmpty(), "断言路径未在响应契约中定义");
                require("JSON_EXISTS".equals(a.kind()) && Boolean.TRUE.equals(a.expected()) || "JSON_EQUALS".equals(a.kind())
                        && types.stream().anyMatch(type -> matches(a.expected(), type)), "不支持的候选断言或预期类型");
            }
            checks.add(a);
        }
        var points = f.testPoints().stream().map(p -> {
            var assertions = new LinkedHashMap<>(p.assertions()); assertions.putAll(additions.getOrDefault(p.id(), Map.of()));
            return new TestPoint(p.id(), p.title(), p.covers(), assertions, p.inputOverrides(), p.intentionallyViolatedRule(), p.endAtStepRef(),p.suppressedOutputCalls(),p.generatedFrom());
        }).toList();
        var filled = f.withCalls(nodes, points);
        require(ancestors.equals(ancestors(filled)), "补全不得改变既有执行先后关系，请先由人明确步骤顺序");
        return filled;
    }

    static Map<String, Set<String>> ancestors(Flow f) {
        Map<String, Set<String>> result = new HashMap<>(); f.nodes().forEach(n -> result.put(n.id(), new HashSet<>()));
        if (f.steps() != null) {
            Set<String> previous = new HashSet<>();
            for (var step : f.steps()) {
                for (var call : step.calls()) {
                    result.get(call.id()).addAll(previous);
                    if ("SERIAL".equals(step.mode())) previous.add(call.id());
                }
                step.calls().forEach(c -> previous.add(c.id()));
            }
            return result;
        }
        f.dependencies().forEach(e -> result.get(e.to()).add(e.from()));
        Map<String, String> producer = new HashMap<>(); f.nodes().forEach(n -> n.outputs().forEach(o -> producer.put(o.key(), n.id())));
        for (var n : f.nodes()) {
            List<Binding> bindings = new ArrayList<>(n.inputs().values());
            if(n.preparation()!=null) io.yuan.octopus.preparation.PreparationTools.values(n.preparation()).forEach(v -> bindings.add(new Binding(v.source(),v.value())));
            f.testPoints().forEach(p -> bindings.addAll(p.inputOverrides().getOrDefault(n.id(), Map.of()).values()));
            for (var b : bindings) if (b != null && "context".equals(b.source()) && producer.containsKey(b.value()))
                result.get(n.id()).add(producer.get(b.value()));
            f.testPoints().forEach(p -> p.assertions().getOrDefault(n.id(), List.of()).stream().filter(a -> DatastoreChecks.KIND.equals(a.kind()))
                    .forEach(a -> io.yuan.octopus.verification.VerificationRules.values(DatastoreChecks.plan(a)).stream()
                            .filter(v -> "context".equals(v.source()) && producer.containsKey(v.value()) && !n.id().equals(producer.get(v.value())))
                            .forEach(v -> result.get(n.id()).add(producer.get(v.value())))));
        }
        boolean changed;
        do { changed = false; for (var values : result.values()) for (var id : List.copyOf(values)) changed |= values.addAll(result.get(id)); } while (changed);
        return result;
    }

    /** Conservative JSON Schema traversal: unknown/ref/composed schemas require contract clarification. */
    private static Set<String> responseTypes(Operation operation, String path) {
        jsonPath(path);
        if ("$.status".equals(path)) return Set.of("integer");
        if (operation.responseSchema() == null || !("$.body".equals(path) || path.startsWith("$.body.") || path.startsWith("$.body["))) return Set.of();
        String suffix = path.substring("$.body".length());
        Set<String> types = new HashSet<>();
        for (var schema : operation.responseSchema().values()) {
            Object current = schema;
            var tokens = java.util.regex.Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[([0-9]+)]").matcher(suffix);
            while (tokens.find()) {
                if (!(current instanceof Map<?, ?> map)) { current = null; break; }
                if (tokens.group(1) != null && map.get("properties") instanceof Map<?, ?> props) current = props.get(tokens.group(1));
                else if (tokens.group(2) != null && "array".equals(map.get("type"))) current = map.get("items");
                else { current = null; break; }
            }
            if (current instanceof Map<?, ?> map && map.get("type") instanceof String type && TYPES.contains(type)) types.add(type);
        }
        return types;
    }
    private static boolean knownValue(Object value, Object definition) {
        if (!(definition instanceof Map<?, ?> schema) || !(schema.get("type") instanceof String type) || !matches(value, type)) return false;
        if (value instanceof Map<?, ?> map) {
            if (schema.get("required") instanceof List<?> required && !map.keySet().containsAll(required)) return false;
            if (map.isEmpty()) return true;
            if (!(schema.get("properties") instanceof Map<?, ?> properties)) return false;
            return map.entrySet().stream().allMatch(e -> knownValue(e.getValue(), properties.get(e.getKey())));
        }
        if (value instanceof List<?> list) return list.stream().allMatch(item -> knownValue(item, schema.get("items")));
        return !(schema.get("enum") instanceof List<?> values) || values.stream().anyMatch(v -> canonical(v).equals(canonical(value)));
    }
    static boolean sensitive(String name) { return name != null && (io.yuan.octopus.preparation.PreparationTools.sensitive(name) || name.toLowerCase(Locale.ROOT).matches("auth\\..*|.*(password|passwd|secret|token|authorization|cookie|api.?key|credential).*" )); }
    private static boolean hasSensitiveField(Object value) {
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().anyMatch(e -> sensitive(String.valueOf(e.getKey())) || hasSensitiveField(e.getValue()));
        return value instanceof List<?> list && list.stream().anyMatch(FlowFillValidator::hasSensitiveField);
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> modelPath(Map<String, Object> path) {
        return (Map<String, Object>) redact(read(canonical(path), Map.class), "");
    }
    private static Object redact(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("source") instanceof String source && Set.of("context","secret","result","input","request").contains(source) && map.containsKey("value")) return map;
            if ("literal".equals(map.get("source")) && sensitive(key)) return Map.of("source", "redacted");
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), redact(v, String.valueOf(k)))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> redact(v, key)).toList();
        return sensitive(key) ? "[redacted]" : value;
    }
}
