package io.yuan.octopus.scenario.dsl;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.preparation.PreparationPlan;
import io.yuan.octopus.scenario.dsl.DslModel.*;
import io.yuan.octopus.scenario.dsl.DslModel.Step;
import io.yuan.octopus.scenario.testing.PollAuthorizationRules;
import io.yuan.octopus.scenario.testing.ResponseSchemaChecks;
import java.math.BigDecimal;
import java.util.*;

/**
 * Deterministic DSL → ordered Flow translation (I1). Declared items become locked bindings, outputs and
 * assertions; the only derivations are rule-based (FR-DERIVE-02): unique success status from the contract,
 * same-name-same-type dependencies, function versions, authorized poll bounds. Everything else that is still
 * missing is returned as {@link Pending} instead of being guessed, and the draft is never confirmed here
 * (FR-CONV-01). Every produced item carries its {@link Provenance} (FR-CONV-02).
 *
 * <p>The output is always strictly linear: one SERIAL step per DSL step, no dependency edges (FR-LIN-01).
 */
public final class DslTranslator {

    /** Resolves {@code METHOD /path} to one pinned, contract-materialized operation version. */
    public interface ApiResolver {
        Resolved resolve(String method, String path, String service);
    }

    /** A resolved API: the operation (with ports and schemas) and the code's path template. */
    public static final class Resolved {
        public final Operation operation;
        public final String templatePath;
        public final List<String> warnings;
        public Resolved(Operation operation, String templatePath, List<String> warnings) {
            this.operation = operation;
            this.templatePath = templatePath;
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
        public Operation operation() { return operation; }
        public String templatePath() { return templatePath; }
        public List<String> warnings() { return warnings; }
    }

    /** Thrown by resolvers; {@code candidates} help the user disambiguate. */
    public static final class ResolveException extends RuntimeException {
        public final String code;
        public final List<String> candidates;
        public ResolveException(String code, String message, List<String> candidates) {
            super(message);
            this.code = code;
            this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** Resolves {@code ${fn.name(...)}} to a catalog entry usable in a request (FR-FN-01). */
    public interface FunctionResolver {
        Optional<FunctionRef> resolve(String name, String version);
    }

    /** Catalog id, pinned version, positional parameter names/types, the output to use, and allowed stages. */
    public static final class FunctionRef {
        public final String id, version, output, outputType;
        public final List<String> params, types, stages;
        public FunctionRef(String id, String version, List<String> params, List<String> types, String output, String outputType, List<String> stages) {
            this.id = id;
            this.version = version;
            this.params = List.copyOf(params);
            this.types = List.copyOf(types);
            this.output = output;
            this.outputType = outputType;
            this.stages = List.copyOf(stages);
        }
        public String id() { return id; }
        public String version() { return version; }
        public List<String> params() { return params; }
        public List<String> types() { return types; }
        public String output() { return output; }
        public List<String> stages() { return stages; }
    }

    /** Active poll authorization of an operation, if any (FR-POLL-01). */
    public interface PollResolver {
        Optional<PollAuthorizationRules.Grant> grant(Operation operation);
    }

    /** Where a produced item came from: DSL (declared), CONTRACT, RULE (deterministic derivation), AUTHORIZATION. */
    public static final class Provenance {
        public final String nodeId, item, source, detail;
        public final int line;
        public Provenance(String nodeId, String item, String source, int line, String detail) {
            this.nodeId = nodeId;
            this.item = item;
            this.source = source;
            this.line = line;
            this.detail = detail;
        }
    }

    /** Something the draft still lacks; never guessed in I1. */
    public static final class Pending {
        public final String nodeId, item, reason;
        public final int line;
        public Pending(String nodeId, String item, String reason, int line) {
            this.nodeId = nodeId;
            this.item = item;
            this.reason = reason;
            this.line = line;
        }
    }

    public static final class ScenarioResult {
        public final String scenarioId, name;
        public final Flow flow;
        public final List<Operation> operations;
        public final List<String> roles;
        public final List<Provenance> provenance;
        public final List<Pending> pending;
        public final List<Diagnostic> diagnostics;
        public ScenarioResult(String scenarioId, String name, Flow flow, List<Operation> operations, List<String> roles,
                              List<Provenance> provenance, List<Pending> pending, List<Diagnostic> diagnostics) {
            this.scenarioId = scenarioId;
            this.name = name;
            this.flow = flow;
            this.operations = operations;
            this.roles = roles;
            this.provenance = provenance;
            this.pending = pending;
            this.diagnostics = diagnostics;
        }
        public boolean translated() {
            return flow != null && diagnostics.stream().noneMatch(Diagnostic::error);
        }
    }

    public static final class Result {
        public final String sourceHash;
        public final List<ScenarioResult> scenarios;
        public final List<Diagnostic> diagnostics;
        public Result(String sourceHash, List<ScenarioResult> scenarios, List<Diagnostic> diagnostics) {
            this.sourceHash = sourceHash;
            this.scenarios = scenarios;
            this.diagnostics = diagnostics;
        }
    }

    private final ApiResolver apis;
    private final FunctionResolver functions;
    private final PollResolver polls;

    public DslTranslator(ApiResolver apis, FunctionResolver functions, PollResolver polls) {
        this.apis = apis;
        this.functions = functions;
        this.polls = polls;
    }

    public Result translate(String source, String flowPrefix) {
        var doc = DslParser.parse(source);
        String hash = io.yuan.octopus.scenario.testing.TestingJson.hash(Map.of("dsl", source == null ? "" : source));
        if (doc.hasErrors()) return new Result(hash, List.of(), doc.diagnostics);
        var results = new ArrayList<ScenarioResult>();
        for (Scenario s : doc.scenarios) results.add(new Builder(doc, s, flowPrefix).build());
        return new Result(hash, results, doc.diagnostics);
    }

    /** Stable scenario id (FR-GIT-05): explicit {@code id}, else derived from the name. */
    public static String scenarioId(Scenario s) {
        if (s.id != null) return s.id;
        String digest = io.yuan.octopus.scenario.testing.TestingJson.hash(Map.of("name", s.name == null ? "" : s.name));
        return "s-" + digest.substring(0, 12);
    }

    private final class Builder {
        final Document doc;
        final Scenario scenario;
        final String flowId;
        final List<Diagnostic> d = new ArrayList<>();
        final List<Provenance> provenance = new ArrayList<>();
        final List<Pending> pending = new ArrayList<>();
        final Map<String, Variable> initial = new LinkedHashMap<>();
        /** alias → node id and response schema of the producer. */
        final Map<String, String> aliasNode = new HashMap<>();
        final Map<String, Map<String, Object>> aliasSchema = new HashMap<>();
        /** node id → outputs created on demand when later steps reference fields. */
        final Map<String, Map<String, Output>> outputs = new LinkedHashMap<>();
        final List<Operation> operations = new ArrayList<>();
        int toolCounter;

        Builder(Document doc, Scenario scenario, String prefix) {
            this.doc = doc;
            this.scenario = scenario;
            this.flowId = (prefix == null || prefix.isBlank() ? "dsl" : prefix) + "." + scenarioId(scenario);
        }

        ScenarioResult build() {
            scenario.inputs.forEach((name, type) -> initial.put(name, new Variable(type, false)));
            var all = new ArrayList<Step>(doc.background);
            all.addAll(scenario.steps);
            var roles = DslChecker.roles(all, null);
            var nodes = new ArrayList<FlowNode>();
            var assertions = new LinkedHashMap<String, List<Assertion>>();
            String negativeStep = null, negativeCall = null;
            for (int i = 0; i < all.size(); i++) {
                Step step = all.get(i);
                String nodeId = "c" + (i + 1);
                var built = node(step, nodeId, false);
                if (built == null) continue;
                nodes.add(built.node());
                assertions.put(nodeId, built.assertions());
                Integer status = DslChecker.status(step);
                if (status != null && status >= 400) { negativeStep = "s" + (i + 1); negativeCall = nodeId; }
            }
            if (d.stream().anyMatch(Diagnostic::error))
                return new ScenarioResult(scenarioId(scenario), scenario.name, null, operations, roles, provenance, pending, d);
            // Cleanup references create producer outputs too, so it is translated before outputs are attached.
            var cleanup = new ArrayList<CleanupCall>();
            for (int i = 0; i < scenario.cleanup.size(); i++) {
                var c = cleanup(scenario.cleanup.get(i), "k" + (i + 1));
                if (c != null) cleanup.add(c);
            }
            // Outputs are attached after all steps are known (references create them on demand).
            var finalNodes = new ArrayList<FlowNode>();
            for (FlowNode n : nodes) {
                var outs = new ArrayList<>(outputs.getOrDefault(n.id(), Map.of()).values());
                finalNodes.add(new FlowNode(n.id(), n.label(), n.operationRef(), n.inputs(), outs, n.states(), n.waitMillis(), n.preparation(), n.poll()));
            }
            var steps = new ArrayList<FlowStep>();
            for (int i = 0; i < finalNodes.size(); i++)
                steps.add(new FlowStep("s" + (i + 1), finalNodes.get(i).label(), "SERIAL", List.of(finalNodes.get(i))));
            var point = new TestPoint("tp1", scenario.name, finalNodes.stream().map(FlowNode::id).collect(java.util.stream.Collectors.toList()), assertions, Map.of(), null,
                    negativeStep, negativeCall == null ? null : List.of(negativeCall));
            Flow flow = null;
            if (d.stream().noneMatch(Diagnostic::error))
                flow = new Flow(flowId, 0, scenario.name, initial, finalNodes, List.of(), List.of(), List.of(point), steps,
                        cleanup.isEmpty() ? null : cleanup);
            return new ScenarioResult(scenarioId(scenario), scenario.name, flow, operations, roles, provenance, pending, d);
        }

        final class Built {
            final FlowNode node;
            final List<Assertion> assertions;
            Built(FlowNode node, List<Assertion> assertions) { this.node = node; this.assertions = assertions; }
            FlowNode node() { return node; }
            List<Assertion> assertions() { return assertions; }
        }

        Built node(Step step, String nodeId, boolean isCleanup) {
            Resolved resolved;
            try {
                resolved = apis.resolve(step.method, step.path, step.service);
            } catch (ResolveException e) {
                d.add(new Diagnostic("ERROR", e.code, e.getMessage(), step.line, step.column,
                        e.candidates.isEmpty() ? null : "候选：" + String.join("；", e.candidates.subList(0, Math.min(5, e.candidates.size())))));
                return null;
            }
            var op = resolved.operation();
            if (operations.stream().noneMatch(o -> o.ref().equals(op.ref()))) operations.add(op);
            provenance.add(new Provenance(nodeId, "operation", "DSL", step.line, op.method() + " " + op.path() + " → " + op.ref()));
            resolved.warnings().forEach(w -> d.add(new Diagnostic("WARNING", "CONTRACT_WARNING", w, step.line, step.column, null)));
            var inputs = new LinkedHashMap<String, Binding>();
            var plan = new PlanBuilder(nodeId);
            var bodyFields = new LinkedHashMap<String, Value>();
            // Path parameters map by position: the DSL placeholder names need not match the code's names.
            var dslParams = new ArrayList<>(DslChecker.pathParams(step.path));
            var codeParams = new ArrayList<>(DslChecker.pathParams(resolved.templatePath()));
            var concrete = concreteSegments(step.path, resolved.templatePath());
            for (int i = 0; i < codeParams.size(); i++) {
                String port = "path." + codeParams.get(i);
                if (concrete.containsKey(codeParams.get(i))) {
                    var def = op.inputs().get(port);
                    String text = concrete.get(codeParams.get(i));
                    Object value = def != null && ("integer".equals(def.type()) || "number".equals(def.type())) ? DslExpressions.literal(text) : text;
                    if (def != null && !io.yuan.octopus.scenario.testing.IrValidator.matches(value, def.type()))
                        d.add(new Diagnostic("ERROR", "TYPE_MISMATCH", "路径值 " + text + " 不符合 " + port + " 的类型 " + def.type(), step.line, step.column, null));
                    else {
                        inputs.put(port, new Binding("literal", value));
                        provenance.add(new Provenance(nodeId, port, "DSL", step.line, "路径中的具体值"));
                    }
                }
                if (i < dslParams.size() && step.with.containsKey(dslParams.get(i))) {
                    bind(nodeId, port, op, step.with.get(dslParams.get(i)), inputs);
                }
            }
            for (var e : step.with.entrySet()) {
                if (dslParams.contains(e.getKey())) continue;
                String key = e.getKey();
                if (op.inputs().containsKey("body." + key)) bodyFields.put(key, e.getValue());
                else if (op.inputs().containsKey("query." + key)) bind(nodeId, "query." + key, op, e.getValue(), inputs);
                else if (headerPort(op, key) != null) bind(nodeId, headerPort(op, key), op, e.getValue(), inputs);
                else if (op.inputs().containsKey("body")) bodyFields.put(key, e.getValue());
                else d.add(new Diagnostic("ERROR", "PARAM_UNKNOWN", "参数 " + key + " 不在 " + op.method() + " " + op.path() + " 的契约中",
                            e.getValue().line, e.getValue().column, "可用参数：" + String.join("、", new TreeSet<>(op.inputs().keySet()))));
            }
            for (var e : step.query.entrySet()) {
                if (op.inputs().containsKey("query." + e.getKey())) bind(nodeId, "query." + e.getKey(), op, e.getValue(), inputs);
                else d.add(new Diagnostic("ERROR", "PARAM_UNKNOWN", "查询参数 " + e.getKey() + " 不在契约中", e.getValue().line, e.getValue().column, null));
            }
            for (var e : step.headers.entrySet()) {
                String port = headerPort(op, e.getKey());
                if (port != null && !DslChecker.dynamic(e.getValue())) bind(nodeId, port, op, e.getValue(), inputs);
                else plan.header(e.getKey(), e.getValue(), step);
            }
            if (step.body instanceof ObjectValue) bodyFields.putAll(((ObjectValue) step.body).fields);
            else if (step.body != null) {
                if (op.inputs().containsKey("body")) bind(nodeId, "body", op, step.body, inputs);
                else d.add(new Diagnostic("ERROR", "BODY_UNSUPPORTED", "该 API 的契约没有请求体", step.body.line, step.body.column, null));
            }
            if (!bodyFields.isEmpty()) {
                boolean dynamic = bodyFields.values().stream().anyMatch(DslChecker::dynamic);
                boolean wholeBody = !bodyFields.keySet().stream().allMatch(k -> op.inputs().containsKey("body." + k));
                if (dynamic || wholeBody) plan.body(bodyFields, step);
                else bodyFields.forEach((k, v) -> bind(nodeId, "body." + k, op, v, inputs));
            }
            if (!isCleanup) deriveDependencies(step, nodeId, op, inputs, plan);
            op.inputs().forEach((port, def) -> {
                if (def.required() && !inputs.containsKey(port) && !plan.supplies(def))
                    pending.add(new Pending(nodeId, port, "必填参数 " + port + "（" + def.type() + "）没有来源；请在 with 中声明，或等待下一增量的 LLM 推导", step.line));
            });
            var assertions = assertions(step, nodeId, op);
            Poll poll = isCleanup ? null : poll(step, nodeId, op);
            if (step.waitMillis > 0) provenance.add(new Provenance(nodeId, "wait", "DSL", step.line, step.waitMillis + " ms"));
            if (step.alias != null && !isCleanup) {
                aliasNode.put(step.alias, nodeId);
                aliasSchema.put(step.alias, successSchema(op));
            }
            // Label: the declared name, else the code's method and path template (stable across DSL spellings).
            String label = step.name != null ? step.name : op.method() + " " + op.path();
            var node = new FlowNode(nodeId, label, op.ref(), inputs, List.of(), List.of(), step.waitMillis, plan.build(), poll);
            return new Built(node, assertions);
        }

        CleanupCall cleanup(Step step, String nodeId) {
            var built = node(step, nodeId, true);
            if (built == null) return null;
            var refs = new ArrayList<Ref>();
            DslChecker.values(step).forEach(v -> DslChecker.collect(v, refs));
            String resource = built.node().inputs().values().stream().filter(b -> "context".equals(b.source())).map(b -> String.valueOf(b.value()))
                    .filter(k -> !initial.containsKey(k)).findFirst().orElse(null);
            if (resource == null) {
                d.add(new Diagnostic("ERROR", "CLEANUP_RESOURCE", "清理调用必须以参数形式引用本场景产生的资源", step.line, step.column, null));
                return null;
            }
            return new CleanupCall(built.node(), built.assertions(), resource, List.of("PASS", "FAIL", "INCONCLUSIVE"), false, 30_000);
        }

        String headerPort(Operation op, String name) {
            for (String key : op.inputs().keySet())
                if (key.startsWith("header.") && key.substring(7).equalsIgnoreCase(name)) return key;
            return null;
        }

        void bind(String nodeId, String port, Operation op, Value v, Map<String, Binding> inputs) {
            var def = op.inputs().get(port);
            String type = def == null ? null : def.type();
            Binding b = binding(v, type, port);
            if (b == null) return;
            inputs.put(port, b);
            provenance.add(new Provenance(nodeId, port, "DSL", v.line, describe(b)));
        }

        /** Locked binding for a literal or reference; dynamic values are routed through the preparation plan instead. */
        Binding binding(Value v, String type, String where) {
            if (v instanceof Literal) {
                Literal l = (Literal) v;
                Object value = l.value instanceof BigDecimal && "integer".equals(type) && ((BigDecimal) l.value).stripTrailingZeros().scale() <= 0
                        ? (Object) ((BigDecimal) l.value).longValueExact() : l.value;
                if (type != null && !io.yuan.octopus.scenario.testing.IrValidator.matches(value, type)) {
                    d.add(new Diagnostic("ERROR", "TYPE_MISMATCH", where + " 需要 " + type + "，但写的是 " + kind(value), v.line, v.column, null));
                    return null;
                }
                return new Binding("literal", value);
            }
            if (v instanceof ObjectValue || v instanceof ArrayValue) {
                Object literal = literalTree(v);
                if (literal == null) {
                    d.add(new Diagnostic("ERROR", "FN_POSITION", where + " 的对象或数组中只能包含字面量（引用请放在请求体顶层字段）", v.line, v.column, null));
                    return null;
                }
                return new Binding("literal", literal);
            }
            if (v instanceof Ref) {
                Ref r = (Ref) v;
                if (r.kind == Ref.Kind.ENV) return new Binding("environment", r.name);
                if (r.kind == Ref.Kind.SECRET) {
                    if (type != null && !"string".equals(type))
                        d.add(new Diagnostic("ERROR", "TYPE_MISMATCH", "敏感变量只能绑定文本参数: " + where, v.line, v.column, null));
                    return new Binding("secret", r.name);
                }
                if (r.kind == Ref.Kind.INPUT) return new Binding("context", r.name);
                String key = contextKey(r, type, v);
                return key == null ? null : new Binding("context", key);
            }
            d.add(new Diagnostic("ERROR", "FN_POSITION", where + " 不能使用函数或文本拼接", v.line, v.column, null));
            return null;
        }

        /** Creates (once) the producer output for {@code ${alias.path}} and returns its context key. */
        String contextKey(Ref r, String expectedType, Value at) {
            String producer = aliasNode.get(r.name);
            if (producer == null) {
                d.add(new Diagnostic("ERROR", "REF_UNDEFINED", "引用了未定义的步骤结果: " + r.text(), at.line, at.column, null));
                return null;
            }
            String jsonPath = "$" + r.path;
            String key = (r.name + r.path.replaceAll("\\[(\\d+)]", ".$1")).replaceAll("^\\.", "");
            var schema = aliasSchema.get(r.name);
            String type = schemaType(schema, r.path);
            if (type == null && schema != null && !schema.isEmpty() && !unresolved(schema)) {
                d.add(new Diagnostic("ERROR", "REF_FIELD_UNKNOWN", "上游回应契约中没有字段 " + jsonPath + "（" + r.text() + "）", at.line, at.column, null));
                return null;
            }
            if (type == null) {
                type = expectedType == null ? "string" : expectedType;
                d.add(new Diagnostic("WARNING", "REF_TYPE_ASSUMED", "上游回应契约不完整，" + r.text() + " 的类型按使用处推定为 " + type, at.line, at.column, null));
            }
            if (expectedType != null && !compatible(type, expectedType)) {
                d.add(new Diagnostic("ERROR", "TYPE_MISMATCH", r.text() + " 的类型是 " + type + "，但目标参数需要 " + expectedType, at.line, at.column, null));
                return null;
            }
            var map = outputs.computeIfAbsent(producer, k -> new LinkedHashMap<>());
            if (!map.containsKey(key)) {
                map.put(key, new Output(key, jsonPath, type, false));
                provenance.add(new Provenance(producer, "output." + key, "DSL", at.line, "as: " + r.name + " → " + jsonPath));
            }
            return key;
        }

        /** FR-DERIVE-02: missing required inputs from upstream fields with the same name and type (or alias + "Id" → id). */
        void deriveDependencies(Step step, String nodeId, Operation op, Map<String, Binding> inputs, PlanBuilder plan) {
            var candidatesAliases = step.from.isEmpty() ? new ArrayList<>(aliasNode.keySet()) : step.from;
            for (var e : op.inputs().entrySet()) {
                var def = e.getValue();
                if (!def.required() || inputs.containsKey(e.getKey()) || plan.supplies(def)) continue;
                String name = def.name();
                var matches = new ArrayList<String[]>();
                for (String alias : candidatesAliases) {
                    var schema = aliasSchema.get(alias);
                    if (schema == null) continue;
                    String direct = schemaType(schema, "." + name);
                    if (direct != null && compatible(direct, def.type())) matches.add(new String[] {alias, "." + name});
                    else if (name.equalsIgnoreCase(alias + "Id") || name.equalsIgnoreCase(alias + "_id")) {
                        String id = schemaType(schema, ".id");
                        if (id != null && compatible(id, def.type())) matches.add(new String[] {alias, ".id"});
                    }
                }
                if (matches.size() == 1) {
                    var ref = new Ref(Ref.Kind.STEP, matches.get(0)[0], matches.get(0)[1]);
                    ref.line = step.line;
                    ref.column = step.column;
                    String key = contextKey(ref, def.type(), ref);
                    if (key != null) {
                        inputs.put(e.getKey(), new Binding("context", key));
                        provenance.add(new Provenance(nodeId, e.getKey(), "RULE", step.line, "同名同类型依赖 ← " + ref.text() + "（待核对）"));
                    }
                } else if (matches.size() > 1) {
                    pending.add(new Pending(nodeId, e.getKey(), "多个上游字段都可以作为 " + e.getKey() + " 的来源："
                            + String.join("、", matches.stream().map(m -> "${" + m[0] + m[1] + "}").collect(java.util.stream.Collectors.toList())) + "；请在 with 中明确", step.line));
                }
            }
        }

        List<Assertion> assertions(Step step, String nodeId, Operation op) {
            var result = new ArrayList<Assertion>();
            Integer status = DslChecker.status(step);
            if (status != null) {
                result.add(new Assertion("status", "STATUS_EQUALS", null, status));
                provenance.add(new Provenance(nodeId, "status", "DSL", step.line, String.valueOf(status)));
            } else {
                var success = successCodes(op);
                if (success.size() == 1) {
                    result.add(new Assertion("status", "STATUS_EQUALS", null, Integer.valueOf(success.get(0))));
                    provenance.add(new Provenance(nodeId, "status", "CONTRACT", step.line, "契约中唯一的成功状态码 " + success.get(0)));
                } else pending.add(new Pending(nodeId, "status", success.isEmpty() ? "契约没有成功状态码，请声明 status" : "契约有多个成功状态码 " + success + "，请声明 status", step.line));
            }
            int n = 0;
            for (Expect e : step.expects) {
                if (e.kind == Expect.Kind.STATUS) continue;
                if (e.kind == Expect.Kind.JSON_EQUALS || e.kind == Expect.Kind.JSON_EXISTS) {
                        String id = "a" + (++n);
                        result.add(new Assertion(id, e.kind.name(), e.path, e.kind == Expect.Kind.JSON_EXISTS ? Boolean.TRUE : e.expected));
                        provenance.add(new Provenance(nodeId, "assertion." + id, "DSL", e.line, DslRenderer.expect(e)));
                        var schema = successSchema(op);
                        if (schema != null && !schema.isEmpty() && !unresolved(schema) && schemaType(schema, e.path.substring(1)) == null)
                            d.add(new Diagnostic("WARNING", "ASSERT_PATH_UNKNOWN", "回应契约中没有 " + e.path + "，请核对", e.line, e.column, null));
                } else if (e.kind == Expect.Kind.SCHEMA) {
                    String id = "a" + (++n);
                    result.add(new Assertion(id, ResponseSchemaChecks.KIND, "$", null));
                    provenance.add(new Provenance(nodeId, "assertion." + id, "DSL", e.line, "回应符合契约"));
                } else pending.add(new Pending(nodeId, "expect", "需要推导的验证：" + e.text, e.line));
            }
            return result;
        }

        Poll poll(Step step, String nodeId, Operation op) {
            if (step.poll == null) return null;
            var grant = polls.grant(op).orElse(null);
            if (grant == null) {
                d.add(new Diagnostic("ERROR", "POLL_UNAUTHORIZED", op.method() + " " + op.path() + " 未被授权轮询（平台不会推断可轮询性）",
                        step.poll.line, 1, "在「轮询授权」中对该 API 显式授权，或改用 wait + 单次查询"));
                return null;
            }
            long interval, timeout;
            if (step.poll.useAuthorization) {
                long[] defaults = PollAuthorizationRules.defaults(grant);
                interval = defaults[0];
                timeout = defaults[1];
            } else {
                interval = step.poll.everyMillis;
                timeout = step.poll.withinMillis;
            }
            String problem = PollAuthorizationRules.problem(interval, timeout, grant.ref(), grant);
            if (problem != null) {
                d.add(new Diagnostic("ERROR", "POLL_EXCEEDS_AUTHORIZATION", problem, step.poll.line, 1, null));
                return null;
            }
            provenance.add(new Provenance(nodeId, "poll", "AUTHORIZATION", step.poll.line, "授权 " + grant.ref() + "：每 " + interval + " ms，最长 " + timeout + " ms"));
            return new Poll(interval, timeout, grant.ref());
        }

        /** Builds the step's preparation plan for function values, interpolation, undeclared headers and assembled bodies. */
        final class PlanBuilder {
            final String nodeId;
            final List<PreparationPlan.Call> calls = new ArrayList<>();
            final Map<String, PreparationPlan.Value> headers = new LinkedHashMap<>();
            PreparationPlan.Value body;

            PlanBuilder(String nodeId) { this.nodeId = nodeId; }

            boolean supplies(Port port) {
                return "body".equals(port.location()) && body != null
                        || "header".equals(port.location()) && headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase(port.name()));
            }

            void header(String name, Value v, Step step) {
                var value = value(v, "string");
                if (value == null) return;
                if ("literal".equals(value.source())) value = call("string.value", "1", List.of("value"), List.of(value), "value");
                headers.put(name, value);
                provenance.add(new Provenance(nodeId, "header." + name, "DSL", v.line, DslRenderer.expression(v)));
            }

            void body(Map<String, Value> fields, Step step) {
                var inputs = new LinkedHashMap<String, PreparationPlan.Value>();
                fields.forEach((k, v) -> {
                    var value = value(v, null);
                    if (value != null) inputs.put(k, value);
                });
                String id = "body" + (++toolCounter);
                calls.add(new PreparationPlan.Call(id, "json.object", "1", inputs));
                body = new PreparationPlan.Value("result", id + ".value");
                provenance.add(new Provenance(nodeId, "body", "DSL", step.line, "按字段组装请求体（" + String.join("、", fields.keySet()) + "）"));
            }

            PreparationPlan.Value value(Value v, String type) {
                if (v instanceof Call) return function((Call) v);
                if (v instanceof Interpolation) return interpolation((Interpolation) v);
                if (v instanceof Ref && ((Ref) v).kind == Ref.Kind.SECRET) return new PreparationPlan.Value("secret", ((Ref) v).name);
                if (v instanceof Ref && ((Ref) v).kind == Ref.Kind.ENV) {
                    d.add(new Diagnostic("ERROR", "ENV_POSITION", "环境变量只能绑定契约中声明的参数（预处理中暂不支持）", v.line, v.column, null));
                    return null;
                }
                Binding b = binding(v, type, "参数");
                if (b == null) return null;
                return new PreparationPlan.Value(b.source(), b.value());
            }

            PreparationPlan.Value function(Call c) {
                var ref = functions.resolve(c.function, c.version).orElse(null);
                if (ref == null) {
                    d.add(new Diagnostic("ERROR", "FN_UNKNOWN", "函数目录中没有 " + c.function + (c.version == null ? "" : "@" + c.version) + "（可能未发布或已停用）",
                            c.line, c.column, "在「函数目录」中检索可用条目"));
                    return null;
                }
                if (!ref.stages().contains("REQUEST_VALUE") && !ref.stages().contains("REQUEST_TRANSFORM")) {
                    d.add(new Diagnostic("ERROR", "FN_STAGE", c.function + " 不能用于请求值（阶段：" + ref.stages() + "）", c.line, c.column, null));
                    return null;
                }
                if (c.args.size() > ref.params().size()) {
                    d.add(new Diagnostic("ERROR", "FN_ARGS", c.function + " 最多接受 " + ref.params().size() + " 个参数", c.line, c.column, "参数顺序：" + ref.params()));
                    return null;
                }
                var values = new ArrayList<PreparationPlan.Value>();
                for (int i = 0; i < c.args.size(); i++) {
                    var value = value(c.args.get(i), ref.types().get(i));
                    if (value == null) return null;
                    values.add(value);
                }
                provenance.add(new Provenance(nodeId, "function." + ref.id(), "DSL", c.line, ref.id() + "@" + ref.version() + (c.version == null ? "（版本按目录固定）" : "")));
                return call(ref.id(), ref.version(), ref.params().subList(0, values.size()), values, ref.output());
            }

            PreparationPlan.Value interpolation(Interpolation in) {
                PreparationPlan.Value acc = null;
                for (Object part : in.parts) {
                    PreparationPlan.Value next;
                    if (part instanceof String) next = new PreparationPlan.Value("literal", part);
                    else {
                        next = value((Value) part, null);
                        if (next == null) return null;
                        if ("context".equals(next.source()) || "result".equals(next.source()))
                            next = call("string.value", "1", List.of("value"), List.of(next), "value");
                    }
                    acc = acc == null ? next : call("string.concat", "1", List.of("prefix", "value"), List.of(acc, next), "value");
                }
                return acc;
            }

            PreparationPlan.Value call(String tool, String version, List<String> names, List<PreparationPlan.Value> values, String output) {
                var inputs = new LinkedHashMap<String, PreparationPlan.Value>();
                for (int i = 0; i < values.size(); i++) inputs.put(names.get(i), values.get(i));
                String id = "f" + (++toolCounter);
                calls.add(new PreparationPlan.Call(id, tool, version, inputs));
                return new PreparationPlan.Value("result", id + "." + output);
            }

            PreparationPlan build() {
                if (calls.isEmpty()) return null;
                return new PreparationPlan(List.copyOf(calls), body, new LinkedHashMap<>(headers));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** For {@code /orders/123} matched to {@code /orders/{orderId}}: orderId → "123". */
    static Map<String, String> concreteSegments(String dslPath, String template) {
        var result = new LinkedHashMap<String, String>();
        String[] a = dslPath.split("/"), b = template.split("/");
        if (a.length != b.length) return result;
        for (int i = 0; i < a.length; i++)
            if (b[i].startsWith("{") && b[i].endsWith("}") && !(a[i].startsWith("{") && a[i].endsWith("}")))
                result.put(b[i].substring(1, b[i].length() - 1), a[i]);
        return result;
    }

    /** Whether a DSL path (template or concrete) matches a code path template. */
    public static boolean pathMatches(String dslPath, String template) {
        String[] a = trim(dslPath).split("/"), b = trim(template).split("/");
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            boolean dslParam = a[i].startsWith("{") && a[i].endsWith("}"), codeParam = b[i].startsWith("{") && b[i].endsWith("}");
            if (codeParam) continue;
            if (dslParam || !a[i].equals(b[i])) return false;
        }
        return true;
    }

    private static String trim(String p) {
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    static List<String> successCodes(Operation op) {
        if (op.responseSchema() == null) return List.of();
        return op.responseSchema().keySet().stream().filter(k -> k.matches("2[0-9]{2}")).sorted().collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> successSchema(Operation op) {
        var codes = successCodes(op);
        if (codes.size() != 1) return null;
        Object schema = op.responseSchema().get(codes.get(0));
        return schema instanceof Map ? (Map<String, Object>) schema : null;
    }

    /** Type at a field path ({@code .a.b[0]}) of a JSON schema, or null when the path is not declared. */
    @SuppressWarnings("unchecked")
    static String schemaType(Map<String, Object> schema, String path) {
        if (schema == null) return null;
        Object current = schema;
        var m = java.util.regex.Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)]").matcher(path);
        while (m.find()) {
            if (!(current instanceof Map)) return null;
            var node = (Map<String, Object>) current;
            if (m.group(1) != null) {
                Object props = node.get("properties");
                if (!(props instanceof Map) || !((Map<?, ?>) props).containsKey(m.group(1))) return null;
                current = ((Map<?, ?>) props).get(m.group(1));
            } else {
                Object items = node.get("items");
                if (!(items instanceof Map)) return null;
                current = items;
            }
        }
        if (!(current instanceof Map)) return null;
        var node = (Map<String, Object>) current;
        Object type = node.get("type");
        if (type instanceof String) return (String) type;
        return node.containsKey("properties") ? "object" : null;
    }

    static boolean unresolved(Map<String, Object> schema) {
        return Boolean.TRUE.equals(schema.get("x-octopus-unresolved"));
    }

    static boolean compatible(String actual, String expected) {
        return expected == null || actual.equals(expected) || "integer".equals(actual) && "number".equals(expected)
                || "any".equals(expected);
    }

    private static Object literalTree(Value v) {
        if (v instanceof Literal) return ((Literal) v).value == null ? NULL : ((Literal) v).value;
        if (v instanceof ObjectValue) {
            var o = (ObjectValue) v;
            var map = new LinkedHashMap<String, Object>();
            for (var e : o.fields.entrySet()) {
                Object child = literalTree(e.getValue());
                if (child == null) return null;
                map.put(e.getKey(), child == NULL ? null : child);
            }
            return map;
        }
        if (v instanceof ArrayValue) {
            var a = (ArrayValue) v;
            var list = new ArrayList<Object>();
            for (Value item : a.items) {
                Object child = literalTree(item);
                if (child == null) return null;
                list.add(child == NULL ? null : child);
            }
            return list;
        }
        return null;
    }

    private static final Object NULL = new Object();

    private static String kind(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Long) return "integer";
        if (v instanceof Number) return "number";
        return v.getClass().getSimpleName();
    }

    private static String describe(Binding b) {
        switch (b.source()) {
            case "literal": return "字面量 " + b.value();
            case "context": return "引用 ${" + b.value() + "}";
            case "secret": return "敏感变量 " + b.value();
            case "environment": return "环境变量 " + b.value();
            default: return b.source();
        }
    }
}
