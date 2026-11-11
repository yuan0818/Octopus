package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import java.util.*;

final class TestingFixtures {
    static Operation login() {
        return new Operation(
                "auth.login",
                "v1",
                "account",
                "auth",
                "POST",
                "/login",
                Map.of("body", new Port("body", "$", "object", true, null)),
                Map.of("type", "object"),
                Map.of("200", Map.of("type", "object")),
                List.of(),
                new Source("MANUAL", "fixture", "fixture"),
                List.of("http", "json"),
                null);
    }

    static Operation verify() {
        return new Operation(
                "auth.verify",
                "v1",
                "account",
                "auth",
                "POST",
                "/verify",
                Map.of("token", new Port("header", "Authorization", "string", true, "Bearer ")),
                Map.of("type", "object"),
                Map.of("200", Map.of("type", "object")),
                List.of(),
                new Source("MANUAL", "fixture", "fixture"),
                List.of("http", "json"),
                null);
    }

    static Flow flow() {
        var login =
                new FlowNode(
                        "login",
                        "Login",
                        login().ref(),
                        Map.of("body", new Binding("context", "credentials")),
                        List.of(new Output("auth.token", "$.token", "string", true)),
                        List.of(),
                        0);
        var verify =
                new FlowNode(
                        "verify",
                        "Verify",
                        verify().ref(),
                        Map.of("token", new Binding("context", "auth.token")),
                        List.of(),
                        List.of(),
                        0);
        var point =
                new TestPoint(
                        "normal",
                        "Token propagation",
                        List.of("verify"),
                        Map.of(
                                "login",
                                List.of(new Assertion("status", "STATUS_EQUALS", null, 200)),
                                "verify",
                                List.of(
                                        new Assertion("status", "STATUS_EQUALS", null, 200),
                                        new Assertion("ok", "JSON_EQUALS", "$.ok", true))),
                        Map.of(),
                        null);
        return new Flow(
                "twofa",
                0,
                "Two step authentication",
                Map.of("credentials", new Variable("object", true)),
                List.of(verify, login),
                List.of(),
                List.of(),
                List.of(point));
    }

    static TestIR ir() {
        Flow flow = flow();
        TestPoint point = flow.testPoints().getFirst();
        return new TestIR(
                "0.1",
                "twofa.normal",
                "twofa",
                1,
                "confirmed-hash",
                point.id(),
                TestingJson.hash(point),
                flow.initialContext(),
                List.of(login(), verify()),
                List.of(),
                flow.nodes().stream()
                        .map(
                                n ->
                                        new Step(
                                                n.id(),
                                                n.operationRef(),
                                                n.inputs(),
                                                n.outputs(),
                                                n.states(),
                                                point.assertions().get(n.id()),
                                                0))
                        .toList(),
                List.of(),
                List.of("http", "json"),
                null,
                List.of());
    }

    static TestIR withSteps(TestIR ir, List<Step> steps) {
        return new TestIR(
                ir.irVersion(),
                ir.scenarioId(),
                ir.flowId(),
                ir.flowRevision(),
                ir.flowHash(),
                ir.testPointId(),
                ir.oracleHash(),
                ir.initialContext(),
                ir.operations(),
                ir.stateModels(),
                steps,
                ir.dependencies(),
                ir.requiresCapabilities(),
                ir.intentionallyViolatedRule(),
                ir.changeEvidenceRefs());
    }
}
