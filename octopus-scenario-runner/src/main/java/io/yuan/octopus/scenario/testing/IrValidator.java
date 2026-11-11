package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.springframework.stereotype.Component;

import java.util.*;

/** Validates the supported v0.1 semantics and returns one stable topological ordering. */
@Component
public class IrValidator {
    private static final Set<String> TYPES =
            Set.of("string", "integer", "number", "boolean", "object", "array");
    private static final Set<String> KINDS =
            Set.of(
                    "DATA_DEPENDENCY",
                    "STATE_DEPENDENCY",
                    "AUTH_DEPENDENCY",
                    "BUSINESS_DEPENDENCY",
                    "ORDERING_CONSTRAINT");
    private final CapabilityRegistry registry;

    public IrValidator(CapabilityRegistry registry) {
        this.registry = registry;
    }

    public List<Step> validate(TestIR ir) {
        require(ir != null && Set.of("0.1", "0.2").contains(ir.irVersion()), "Unsupported IR version");
        require(!"0.1".equals(ir.irVersion()) || ir.executionSteps() == null, "Legacy IR cannot contain ordered groups");
        identifier(ir.scenarioId(), "scenarioId");
        identifier(ir.flowId(), "flowId");
        require(
                ir.flowRevision() > 0 && ir.flowHash() != null && ir.oracleHash() != null,
                "Missing knowledge lock");
        require(
                ir.initialContext() != null
                        && ir.operations() != null
                        && ir.stateModels() != null
                        && ir.steps() != null
                        && ir.dependencies() != null
                        && ir.requiresCapabilities() != null,
                "Incomplete IR");
        require(
                !ir.steps().isEmpty() && ir.steps().size() <= ("0.2".equals(ir.irVersion()) ? 300 : 200),
                "IR operation limit exceeded or empty");
        Map<String, Operation> operations = new HashMap<>();
        Set<String> capabilities = new TreeSet<>(List.of("http", "json"));
        if ("0.2".equals(ir.irVersion())) capabilities.add("flow.ordered");
        for (var operation : ir.operations()) {
            validateOperation(operation);
            require(
                    operations.put(operation.ref(), operation) == null,
                    "Duplicate operation revision");
            capabilities.addAll(operation.requiresCapabilities());
            if (operation.securityProfile() != null && !operation.securityProfile().isBlank())
                capabilities.add("crypto." + operation.securityProfile());
        }
        require(
                ir.requiresCapabilities().containsAll(capabilities),
                "IR omits required capabilities: " + capabilities);
        registry.require(ir.requiresCapabilities());
        Map<String, Step> steps = new TreeMap<>();
        Map<String, Variable> variables = new HashMap<>(ir.initialContext());
        Map<String, String> producers = new HashMap<>();
        variables.forEach(
                (key, value) -> {
                    identifier(key, "context key");
                    require(
                            value != null && TYPES.contains(value.type()),
                            "Unknown context type: " + key);
                });
        for (var step : ir.steps()) {
            identifier(step.id(), "step id");
            require(steps.put(step.id(), step) == null, "Duplicate step: " + step.id());
            require(
                    operations.containsKey(step.operationRef()),
                    "Unknown operation: " + step.operationRef());
            require(
                    step.inputs() != null
                            && step.outputs() != null
                            && step.states() != null
                            && step.assertions() != null,
                    "Incomplete step: " + step.id());
            require(
                    step.waitMillis() >= 0 && step.waitMillis() <= 60_000,
                    "Wait must be within 0..60000ms");
            PollChecks.validate(step, new HashSet<>(ir.requiresCapabilities()));
            for (var output : step.outputs()) {
                identifier(output.key(), "output key");
                jsonPath(output.path());
                require(TYPES.contains(output.type()), "Unknown output type");
                require(
                        variables.putIfAbsent(
                                        output.key(),
                                        new Variable(output.type(), output.sensitive()))
                                == null,
                        "Multiple writers or initial/output collision: " + output.key());
                producers.put(output.key(), step.id());
            }
        }
        Map<String, Set<String>> dependencies = new TreeMap<>();
        steps.keySet().forEach(id -> dependencies.put(id, new TreeSet<>()));
        for (var edge : ir.dependencies()) {
            require(KINDS.contains(edge.kind()), "Unsupported dependency type");
            require(
                    steps.containsKey(edge.from()) && steps.containsKey(edge.to()),
                    "Unknown dependency endpoint");
            require(
                    edge.reason() != null && !edge.reason().isBlank(),
                    "Dependencies require a reason");
            dependencies.get(edge.to()).add(edge.from());
        }
        Map<String, StateModel> states = new HashMap<>();
        for (var model : ir.stateModels()) {
            identifier(model.modelId(), "state model");
            require(
                    model.revision() != null
                            && model.states() != null
                            && model.transitions() != null,
                    "Incomplete state model");
            require(states.put(model.modelId(), model) == null, "Duplicate state model");
            Set<String> rules = new HashSet<>();
            for (var transition : model.transitions()) {
                identifier(transition.ruleId(), "state rule");
                require(
                        rules.add(transition.ruleId())
                                && model.states().contains(transition.from())
                                && model.states().contains(transition.to()),
                        "Invalid state transition");
            }
        }
        boolean negativeRuleFound =
                ir.intentionallyViolatedRule() == null || ir.intentionallyViolatedRule().isBlank();
        for (var step : steps.values()) {
            Operation operation = operations.get(step.operationRef());
            if(step.preparation()!=null) {
                require(ir.requiresCapabilities().contains("request.prepare"),"IR omits request preparation capability");
                require(!ExtensionUsage.uses(step.preparation()) || ir.requiresCapabilities().contains(ExtensionUsage.CAPABILITY),"IR omits JMeter extension capability");
                PreparationChecks.validate(step.preparation(),operation,step.inputs(),variables,producers,dependencies.get(step.id()));
            }
            for (var input : step.inputs().entrySet()) {
                Port port = operation.inputs().get(input.getKey());
                require(port != null, "Unknown input port: " + step.id() + "." + input.getKey());
                binding(
                        input.getValue(),
                        port.type(),
                        variables,
                        producers,
                        dependencies.get(step.id()));
            }
            operation
                    .inputs()
                    .forEach(
                            (name, port) ->
                                    require(
                                            !port.required() || step.inputs().containsKey(name) || PreparationChecks.supplies(step.preparation(),port),
                                            "Missing required input: " + step.id() + "." + name));
            require(
                    step.assertions().stream().anyMatch(a -> "STATUS_EQUALS".equals(a.kind())),
                    "Every operation requires an explicit status oracle: " + step.id());
            require(
                    step.assertions().stream().filter(a -> "STATUS_EQUALS".equals(a.kind())).count()
                            == 1,
                    "Each operation requires exactly one expected HTTP status");
            Set<String> assertionIds = new HashSet<>();
            require(DatastoreChecks.of(step).size() <= 30, "每个步骤最多配置 30 个数据验证");
            for (var assertion : step.assertions()) {
                identifier(assertion.id(), "assertion id");
                require(assertionIds.add(assertion.id()), "Duplicate assertion id");
                require(
                        Set.of("STATUS_EQUALS", "JSON_EQUALS", "JSON_EXISTS", DatastoreChecks.KIND, ResponseSchemaChecks.KIND)
                                .contains(assertion.kind()),
                        "Unsupported assertion: " + assertion.kind());
                if (ResponseSchemaChecks.KIND.equals(assertion.kind())) {
                    require(ir.requiresCapabilities().contains(ResponseSchemaChecks.CAPABILITY), "IR omits response schema capability");
                    ResponseSchemaChecks.validate(assertion, step, operation);
                } else if (DatastoreChecks.KIND.equals(assertion.kind())) {
                    var check = DatastoreChecks.plan(assertion);
                    require(ir.requiresCapabilities().contains(DatastoreChecks.capability(check)), "IR omits datastore verification capability");
                    DatastoreChecks.bindings(check, step.id(), variables, producers, dependencies.get(step.id()));
                } else if ("STATUS_EQUALS".equals(assertion.kind())) {
                    require(
                            assertion.expected() instanceof Number n
                                    && n.doubleValue() == n.intValue()
                                    && n.intValue() >= 100
                                    && n.intValue() <= 599,
                            "Invalid expected HTTP status");
                } else jsonPath(assertion.path());
            }
            for (var constraint : step.states()) {
                StateModel model = states.get(constraint.modelId());
                require(
                        model != null
                                && model.transitions().stream()
                                        .anyMatch(t -> t.ruleId().equals(constraint.ruleId())),
                        "Unknown state rule");
                context(
                        constraint.entityContext(),
                        "string",
                        variables,
                        producers,
                        dependencies.get(step.id()));
                context(
                        constraint.observedContext(),
                        "string",
                        variables,
                        producers,
                        dependencies.get(step.id()));
                context(
                        constraint.observedEntityContext(),
                        "string",
                        variables,
                        producers,
                        dependencies.get(step.id()));
                require(
                        !constraint.entityContext().equals(constraint.observedEntityContext()),
                        "State observation must declare its observed entity ID separately");
                jsonPath(constraint.afterPath());
                jsonPath(constraint.afterEntityPath());
                if ((constraint.modelId() + "/" + constraint.ruleId())
                        .equals(ir.intentionallyViolatedRule())) {
                    require(
                            step.assertions().stream()
                                    .anyMatch(
                                            a ->
                                                    "STATUS_EQUALS".equals(a.kind())
                                                            && ((Number) a.expected()).intValue()
                                                                    >= 400),
                            "An intentional state violation needs a rejection oracle");
                    negativeRuleFound = true;
                }
            }
        }
        require(negativeRuleFound, "Intentional violation does not reference a used state rule");
        if (ir.intentionallyViolatedRule() != null && !ir.intentionallyViolatedRule().isBlank())
            require(
                    ir.steps().stream()
                                    .flatMap(s -> s.states().stream())
                                    .filter(
                                            c ->
                                                    (c.modelId() + "/" + c.ruleId())
                                                            .equals(ir.intentionallyViolatedRule()))
                                    .count()
                            == 1,
                    "Intentional violation must identify exactly one state check in this flow");
        if ("0.2".equals(ir.irVersion())) {
            var ordered=OrderedFlows.validate(ir,dependencies);
            validateCleanup(ir);
            return ordered;
        }
        require(ir.cleanup()==null || ir.cleanup().isEmpty(),"Legacy IR cannot contain ordered cleanup");
        List<Step> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (done.size() < steps.size()) {
            String next =
                    dependencies.keySet().stream()
                            .filter(
                                    id ->
                                            !done.contains(id)
                                                    && done.containsAll(dependencies.get(id)))
                            .findFirst()
                            .orElse(null);
            require(next != null, "Dependency cycle (including context/state bindings)");
            ordered.add(steps.get(next));
            done.add(next);
        }
        return List.copyOf(ordered);
    }

    private void validateCleanup(TestIR ir) {
        if(ir.cleanup()==null) return;
        require(ir.cleanup().size()<=30,"At most 30 cleanup calls");
        var context=new HashMap<>(ir.initialContext());Set<String> produced=new HashSet<>(), ids=new HashSet<>();
        for(var step:ir.steps()) {
            ids.add(step.id());
            step.outputs().forEach(o->{context.put(o.key(),new Variable(o.type(),o.sensitive()));produced.add(o.key());});
        }
        for(var cleanup:ir.cleanup()) {
            require(cleanup!=null && cleanup.call()!=null && ids.add(cleanup.call().id()),"Cleanup call ID must be unique");
            require(produced.contains(cleanup.resourceKey()),"Cleanup requires an owned resource output from this run");
            require(cleanup.call().inputs().values().stream().anyMatch(b->"context".equals(b.source()) && cleanup.resourceKey().equals(b.value())),
                    "Cleanup request must bind its owned resource key");
            require(cleanup.when()!=null && !cleanup.when().isEmpty() && Set.of("PASS","FAIL","INCONCLUSIVE").containsAll(cleanup.when()),"Unsupported cleanup terminal policy");
            require(cleanup.timeoutMillis()>=1 && cleanup.timeoutMillis()<=60000,"Cleanup timeout must be 1..60000ms");
            require(cleanup.call().outputs().isEmpty() && cleanup.call().waitMillis()==0 && cleanup.call().poll()==null,"Cleanup cannot publish outputs, add delays or poll");
            validate(new TestIR("0.1",ir.scenarioId(),ir.flowId(),ir.flowRevision(),ir.flowHash(),ir.testPointId(),ir.oracleHash(),
                    context,ir.operations(),ir.stateModels(),List.of(cleanup.call()),List.of(),ir.requiresCapabilities(),null,List.of()));
        }
    }

    public void validateOperation(Operation operation) {
        require(operation != null, "Missing operation");
        identifier(operation.operationId(), "operationId");
        require(
                operation.revision() != null
                        && operation.revision().matches("[A-Za-z0-9_.-]{1,80}"),
                "Invalid operation revision");
        identifier(operation.system(), "system");
        identifier(operation.service(), "service");
        require(
                Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                        .contains(operation.method()),
                "Unsupported HTTP method");
        require(
                operation.path() != null
                        && operation.path().startsWith("/")
                        && !operation.path().contains("?")
                        && !operation.path().contains("#"),
                "Operation path must be a path template");
        require(
                operation.inputs() != null
                        && operation.implementations() != null
                        && operation.requiresCapabilities() != null
                        && operation.source() != null,
                "Incomplete API model");
        Set<String> destinations = new HashSet<>();
        operation
                .inputs()
                .forEach(
                        (name, port) -> {
                            identifier(name, "input port");
                            require(
                                    port != null
                                            && Set.of("path", "query", "header", "body")
                                                    .contains(port.location())
                                            && TYPES.contains(port.type()),
                                    "Unsupported input port");
                            require(
                                    port.name() != null
                                            && !port.name().isBlank()
                                            && !port.name().contains("\r")
                                            && !port.name().contains("\n"),
                                    "Invalid input name");
                            if (port.location().equals("header"))
                                require(
                                        port.name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"),
                                        "Invalid HTTP header name");
                            if (!port.location().equals("body"))
                                require(
                                        !Set.of("object", "array").contains(port.type()),
                                        "Structured parameters require a serialization capability");
                            require(
                                    destinations.add(
                                            port.location()
                                                    + ":"
                                                    + (port.location().equals("header")
                                                            ? port.name().toLowerCase(Locale.ROOT)
                                                            : port.name())),
                                    "Overlapping input destinations");
                            if (port.location().equals("path"))
                                require(
                                        port.required()
                                                && operation
                                                        .path()
                                                        .contains("{" + port.name() + "}"),
                                        "Unbound path port");
                        });
        require(
                !destinations.contains("body:$")
                        || operation.inputs().values().stream()
                                        .filter(p -> p.location().equals("body"))
                                        .count()
                                == 1,
                "Root body and body fields cannot overlap");
        var matcher = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(operation.path());
        while (matcher.find()) {
            String name = matcher.group(1);
            require(
                    operation.inputs().values().stream()
                            .anyMatch(p -> p.location().equals("path") && p.name().equals(name)),
                    "Missing path input: " + name);
        }
        for (var impl : operation.implementations())
            require(
                    impl.projectId() > 0
                            && impl.branch() != null
                            && !impl.branch().isBlank()
                            && impl.commitId() != null
                            && impl.commitId().matches("[a-fA-F0-9]{40}")
                            && impl.signature() != null
                            && !impl.signature().isBlank(),
                    "Implementation must pin project, branch, full commit and signature");
    }

    private static void binding(
            Binding binding,
            String type,
            Map<String, Variable> variables,
            Map<String, String> producers,
            Set<String> deps) {
        require(binding != null && binding.source() != null, "Missing input binding");
        switch (binding.source()) {
            case "literal" ->
                    require(matches(binding.value(), type), "Literal input type mismatch: " + type);
            case "context" ->
                    context(String.valueOf(binding.value()), type, variables, producers, deps);
            case "secret" -> {
                identifier(String.valueOf(binding.value()), "secret reference");
                require("string".equals(type), "Secrets must bind string inputs");
            }
            case "environment" -> EnvironmentBindings.reference(binding.value());
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported binding source: " + binding.source());
        }
    }

    private static void context(
            String key,
            String type,
            Map<String, Variable> variables,
            Map<String, String> producers,
            Set<String> deps) {
        Variable variable = variables.get(key);
        require(variable != null, "Undefined context: " + key);
        require(
                type == null
                        || type.equals(variable.type())
                        || type.equals("number") && variable.type().equals("integer"),
                "Context type mismatch: " + key);
        if (producers.containsKey(key)) deps.add(producers.get(key));
    }

    public static boolean matches(Object value, String type) {
        if (value == null) return false;
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
            case "integer" ->
                    value instanceof Number n
                            && Double.isFinite(n.doubleValue())
                            && new java.math.BigDecimal(n.toString()).stripTrailingZeros().scale()
                                    <= 0;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            default -> false;
        };
    }

    public static void jsonPath(String path) {
        require(
                path != null && path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+])*"),
                "Unsupported JSON path (use fields and array indexes): " + path);
    }

    public static void identifier(String id, String field) {
        require(id != null && id.matches("[A-Za-z_][A-Za-z0-9_.-]{0,119}"), "Invalid " + field);
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
