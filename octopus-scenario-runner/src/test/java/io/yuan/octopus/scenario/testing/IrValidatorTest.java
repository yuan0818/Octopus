package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IrValidatorTest {
    private final IrValidator validator = new IrValidator(new CapabilityRegistry());

    @Test
    void ordersByBindingsInsteadOfInputListOrder() {
        assertEquals(
                List.of("login", "verify"),
                validator.validate(TestingFixtures.ir()).stream().map(Step::id).toList());
    }

    @Test
    void rejectsMissingProducer() {
        var ir = TestingFixtures.ir();
        var verify = ir.steps().getFirst();
        var changed =
                new Step(
                        verify.id(),
                        verify.operationRef(),
                        Map.of("token", new Binding("context", "missing.token")),
                        verify.outputs(),
                        verify.states(),
                        verify.assertions(),
                        0);
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        validator.validate(
                                                TestingFixtures.withSteps(
                                                        ir,
                                                        List.of(changed, ir.steps().getLast()))))
                        .getMessage()
                        .contains("Undefined context"));
    }

    @Test
    void rejectsImplicitBindingCycle() {
        var ir = TestingFixtures.ir();
        var login = ir.steps().getLast();
        var changed =
                new Step(
                        login.id(),
                        login.operationRef(),
                        Map.of("body", new Binding("context", "cycle")),
                        List.of(
                                new Output("auth.token", "$.token", "string", true),
                                new Output("cycle", "$.body", "object", false)),
                        login.states(),
                        login.assertions(),
                        0);
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        validator.validate(
                                                TestingFixtures.withSteps(
                                                        ir,
                                                        List.of(ir.steps().getFirst(), changed))))
                        .getMessage()
                        .contains("cycle"));
    }

    @Test
    void rejectsUnknownCapabilitiesAndUnknownFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapabilityRegistry().require(List.of("http", "crypto.payment_v2")));
        var json = TestingJson.GSON.toJsonTree(TestingFixtures.ir()).getAsJsonObject();
        json.addProperty("retryUntilSuccess", true);
        assertThrows(IllegalArgumentException.class, () -> TestingJson.read(json, TestIR.class));
    }

    @Test
    void rejectsUnspecifiedOraclesAndDuplicateWriters() {
        var ir = TestingFixtures.ir();
        var step = ir.steps().getFirst();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validator.validate(
                                TestingFixtures.withSteps(
                                        ir,
                                        List.of(
                                                new Step(
                                                        step.id(),
                                                        step.operationRef(),
                                                        step.inputs(),
                                                        step.outputs(),
                                                        step.states(),
                                                        List.of(),
                                                        0),
                                                ir.steps().getLast()))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validator.validate(
                                TestingFixtures.withSteps(
                                        ir,
                                        List.of(
                                                new Step(
                                                        step.id(),
                                                        step.operationRef(),
                                                        step.inputs(),
                                                        List.of(
                                                                new Output(
                                                                        "auth.token",
                                                                        "$.token",
                                                                        "string",
                                                                        true)),
                                                        step.states(),
                                                        step.assertions(),
                                                        0),
                                                ir.steps().getLast()))));
    }

    @Test
    void rejectsUnknownStateRule() {
        var ir = TestingFixtures.ir();
        var step = ir.steps().getFirst();
        var changed =
                new Step(
                        step.id(),
                        step.operationRef(),
                        step.inputs(),
                        step.outputs(),
                        List.of(
                                new StateConstraint(
                                        "order",
                                        "refund",
                                        "order.id",
                                        "order.state",
                                        "$.state",
                                        "observed.id",
                                        "$.id")),
                        step.assertions(),
                        0);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        validator.validate(
                                TestingFixtures.withSteps(
                                        ir, List.of(changed, ir.steps().getLast()))));
    }
}
