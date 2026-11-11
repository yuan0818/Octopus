package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderedFlowTest {
    final IrValidator validator = new IrValidator(new CapabilityRegistry());
    TestIR ordered(List<Step> calls, List<CallGroup> groups) {
        var old = TestingFixtures.ir();
        return new TestIR("0.2", old.scenarioId(), old.flowId(), old.flowRevision(), old.flowHash(), old.testPointId(),
                old.oracleHash(), old.initialContext(), old.operations(), old.stateModels(), calls, List.of(),
                List.of("http","json","flow.ordered"), null, List.of(), groups);
    }
    @Test void orderedFlowNeverSilentlyReordersForwardReferences() {
        var old = TestingFixtures.ir();
        var calls = validator.validate(old);
        assertEquals(List.of("login","verify"), calls.stream().map(Step::id).toList());
        assertEquals(calls, validator.validate(ordered(calls,List.of(new CallGroup("s1","SERIAL",List.of("login","verify"))))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ordered(calls.reversed(),
                List.of(new CallGroup("s1","SERIAL",List.of("verify","login"))))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ordered(calls,
                List.of(new CallGroup("s1","PARALLEL",List.of("login","verify"))))));
    }
    @Test void legacyLocksAndPayloadsStayByteCompatible() {
        var ir = TestingFixtures.ir();
        String json = TestingJson.canonical(ir);
        assertFalse(json.contains("executionSteps"));
        assertEquals(TestingJson.hash(ir),TestingJson.hash(TestingJson.read(json,TestIR.class)));
        var point = new TestPoint("p","Point",List.of("a"),Map.of(),Map.of(),null);
        assertFalse(TestingJson.canonical(point).contains("endAtStepRef"));
    }
    @Test void allCallsMustBelongToOneGroupAndPrefixesEndAtStepBoundaries() {
        var calls = validator.validate(TestingFixtures.ir());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ordered(calls,
                List.of(new CallGroup("s1","SERIAL",List.of("login"))))));
        var nodes = calls.stream().map(s -> new FlowNode(s.id(),s.id(),s.operationRef(),s.inputs(),s.outputs(),s.states(),0)).toList();
        var groups = List.of(new FlowStep("one","One","SERIAL",List.of(nodes.getFirst())),
                new FlowStep("two","Two","SERIAL",List.of(nodes.getLast())));
        var point = new TestPoint("p","P",List.of("login"),Map.of(),Map.of(),null,"one");
        var flow = new Flow("f",0,"Flow",Map.of(),null,null,List.of(),List.of(point),groups);
        assertEquals(nodes,flow.nodes());
        assertEquals(1,OrderedFlows.prefix(flow,point).size());
        assertEquals(groups,TestingJson.read(TestingJson.canonical(flow),Flow.class).steps());
        assertThrows(IllegalArgumentException.class, () -> OrderedFlows.prefix(flow,
                new TestPoint("p","P",List.of("login"),Map.of(),Map.of(),null,"login")));
    }
}
