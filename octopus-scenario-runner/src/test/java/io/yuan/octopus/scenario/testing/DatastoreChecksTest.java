package io.yuan.octopus.scenario.testing;

import io.yuan.octopus.verification.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class DatastoreChecksTest {
    static VerificationPlan check(String kind, String variable) {
        var key = new VerificationPlan.Value("context", variable);
        return new VerificationPlan(kind, new VerificationPlan.Source("repo", "module", "connection"), "业务结果验证",
                kind.equals("SQL") ? new VerificationPlan.Sql("orders", List.of("status"), false, List.of(new VerificationPlan.Filter("id", "EQ", key))) : null,
                kind.equals("REDIS") ? new VerificationPlan.Redis("GET", "order:", key, null) : null,
                kind.equals("KAFKA") ? new VerificationPlan.Kafka("orders", key) : null,
                new VerificationPlan.Expectation(kind.equals("SQL") ? "$.rows[0].status" : "$.value.status", "EQ", new VerificationPlan.Value("literal", "PAID")), 1000);
    }
    static TestIR withChecks(String variable, String... kinds) {
        var original = TestingFixtures.ir();
        var steps = original.steps().stream().map(s -> {
            var assertions = new ArrayList<>(s.assertions());
            if (s.id().equals("login")) for (String kind : kinds) assertions.add(new Assertion("check_" + kind, DatastoreChecks.KIND, null, check(kind, variable)));
            return new Step(s.id(), s.operationRef(), s.inputs(), s.outputs(), s.states(), assertions, s.waitMillis());
        }).toList();
        List<String> capabilities = new ArrayList<>(original.requiresCapabilities());
        for (String kind : kinds) capabilities.add("verify." + kind.toLowerCase());
        return new TestIR(original.irVersion(), original.scenarioId(), original.flowId(), original.flowRevision(), original.flowHash(), original.testPointId(), original.oracleHash(),
                original.initialContext(), original.operations(), original.stateModels(), steps, original.dependencies(), capabilities, null, List.of());
    }
    @Test void sameStepOutputIsAvailableAndKafkaCapturePrecedesHttp() {
        var registry = new CapabilityRegistry(); var validator = new IrValidator(registry);
        var ir = withChecks("auth.token", "SQL", "REDIS", "KAFKA");
        assertEquals(List.of("login", "verify"), validator.validate(ir).stream().map(Step::id).toList());
        var compiled = new JmxCompiler(validator, registry).compile(ir, new ExecutionProfile(Map.of("account.auth", new Target("http://localhost:8000")), Map.of("password", "must-not-be-in-jmx"), Map.of(), "test"));
        assertTrue(compiled.jmx().indexOf("observe:login:check_KAFKA") < compiled.jmx().indexOf("step:login"));
        assertTrue(compiled.jmx().indexOf("step:login") < compiled.jmx().indexOf("verify:login:check_SQL"));
        assertTrue(compiled.sourceMap().containsKey("verify:login:check_KAFKA"));
        assertFalse(compiled.jmx().contains("must-not-be-in-jmx"));
    }
    @Test void unknownVerificationVariablesAndDriverCapabilitiesFailBeforeExecution() {
        var registry = new CapabilityRegistry();
        assertThrows(IllegalArgumentException.class, () -> new IrValidator(registry).validate(withChecks("invented", "SQL")));
        var ir = withChecks("auth.token", "SQL");
        var missing = new TestIR(ir.irVersion(), ir.scenarioId(), ir.flowId(), ir.flowRevision(), ir.flowHash(), ir.testPointId(), ir.oracleHash(), ir.initialContext(), ir.operations(), ir.stateModels(), ir.steps(), ir.dependencies(), List.of("http", "json"), null, List.of());
        assertThrows(IllegalArgumentException.class, () -> new IrValidator(registry).validate(missing));
    }
    @Test void plannerCarriesReviewedChecksIntoOracleAndCapabilityLock() {
        var store = mock(BusinessKnowledgeStore.class);
        when(store.operation(anyString())).thenAnswer(i -> i.getArgument(0).equals(TestingFixtures.login().ref()) ? TestingFixtures.login() : TestingFixtures.verify());
        var original = TestingFixtures.flow(); var point = original.testPoints().getFirst();
        var assertions = new HashMap<>(point.assertions()); var checks = new ArrayList<>(assertions.get("login"));
        checks.add(new Assertion("db", DatastoreChecks.KIND, null, check("SQL", "auth.token"))); assertions.put("login", checks);
        var updatedPoint = new TestPoint(point.id(), point.title(), point.covers(), assertions, point.inputOverrides(), null);
        var flow = new Flow(original.flowId(), 1, original.name(), original.initialContext(), original.nodes(), original.dependencies(), original.stateModels(), List.of(updatedPoint));
        when(store.flow(flow.flowId(), 1)).thenReturn(new FlowRevision(flow, TestingJson.hash(flow), "CONFIRMED", "reviewer", "now"));
        var planner = new ScenarioPlanner(store, new IrValidator(new CapabilityRegistry()));
        var ir = planner.generate(flow.flowId(), 1, point.id(), List.of());
        assertTrue(ir.requiresCapabilities().contains("verify.sql"));
        assertEquals(TestingJson.hash(updatedPoint), ir.oracleHash());
        assertNotEquals(TestingJson.hash(point), ir.oracleHash());
    }
}
