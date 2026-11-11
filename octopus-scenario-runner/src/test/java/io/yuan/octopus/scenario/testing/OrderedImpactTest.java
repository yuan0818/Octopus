package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderedImpactTest {
    @Test void fixedScopeIncludesIndependentCallsAndExcludesLaterSteps() {
        var first=new FlowNode("first","First","one@v1",Map.of(),List.of(),List.of(),0);
        var last=new FlowNode("last","Last","two@v1",Map.of(),List.of(),List.of(),0);
        var full=new TestPoint("full","Full",List.of("last"),Map.of(),Map.of(),null);
        var prefix=new TestPoint("prefix","Prefix",List.of("first"),Map.of(),Map.of(),null,"s1");
        var flow=new Flow("ordered",1,"Ordered",Map.of(),null,List.of(),List.of(),List.of(full,prefix),List.of(new FlowStep("s1","One","SERIAL",List.of(first)),new FlowStep("s2","Two","SERIAL",List.of(last))));
        var early=BusinessTestImpactBridge.orderedImpact(flow,Map.of("one",Set.of("e1")));
        assertEquals(2,early.size());assertEquals(List.of("last"),early.getFirst().prerequisiteNodes());
        var late=BusinessTestImpactBridge.orderedImpact(flow,Map.of("two",Set.of("e2")));
        assertEquals(1,late.size());assertEquals("full",late.getFirst().testPointId());
    }
}
