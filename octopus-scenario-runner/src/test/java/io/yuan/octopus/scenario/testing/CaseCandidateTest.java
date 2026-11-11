package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CaseCandidateTest {
    private final BusinessKnowledgeStore store=mock(BusinessKnowledgeStore.class);
    private final Operation operation=new Operation("items","v1","shop","items","POST","/items",
            Map.of("quantity",new Port("query","quantity","integer",true,null)),
            Map.of("type","object","x-octopus-parameters",Map.of("quantity",Map.of("type","integer","minimum",1,"maximum",1))),
            Map.of("200",Map.of("type","object")),List.of(),new Source("MANUAL","fixture","fixture"),List.of("http","json"),null);
    private Flow fixture() {
        var call=new FlowNode("create","Create",operation.ref(),Map.of("quantity",new Binding("literal",1)),
                List.of(new Output("item.id","$.id","string",false)),List.of(),0);
        var point=new TestPoint("normal","Create one",List.of("create"),Map.of("create",List.of(new Assertion("status","STATUS_EQUALS",null,200),new Assertion("created","JSON_EQUALS","$.created",true))),Map.of(),null);
        var flow=new Flow("createItems",1,"Create items",Map.of(),null,List.of(),List.of(),List.of(point),List.of(new FlowStep("createStep","Create","SERIAL",List.of(call))));
        when(store.operation(operation.ref())).thenReturn(operation);confirm(flow);return flow;
    }
    private void confirm(Flow flow) {when(store.flow(flow.flowId(),1)).thenReturn(new FlowRevision(flow,TestingJson.hash(flow),"CONFIRMED","reviewer","now"));}
    @Test void generatedBoundariesAreDeduplicatedBoundedAndCarryOrigin() {
        var flow=fixture();var result=new CaseCandidateService(store).generate(new CaseCandidateService.Request(flow.flowId(),1,"normal",2,null,null));
        assertEquals(4,result.total());assertEquals(2,result.candidates().size());assertTrue(result.truncated());
        assertTrue(result.candidates().stream().allMatch(c->c.point().generatedFrom().flowHash().equals(result.flowHash())));
        assertEquals("minimum",result.candidates().getLast().sourceRule().substring(result.candidates().getLast().sourceRule().lastIndexOf('/')+1));
        verify(store,never()).saveDraft(any());
    }
    @Test void negativeCandidateNeedsBusinessOracleThenLowersWithoutSuccessOutputs() {
        var flow=fixture();var service=new CaseCandidateService(store);
        var candidate=service.generate(new CaseCandidateService.Request(flow.flowId(),1,"normal",100,422,"quantity must be positive")).candidates().stream()
                .filter(c->c.sourceRule().endsWith("minimumOutside")).findFirst().orElseThrow();
        assertEquals("BLOCKED",candidate.status());assertEquals("createStep",candidate.point().endAtStepRef());
        var rejected=flow.withCalls(flow.nodes(),List.of(candidate.point()));
        var planner=new ScenarioPlanner(store,new IrValidator(new CapabilityRegistry()));
        assertThrows(IllegalArgumentException.class,()->planner.validateFlow(rejected));
        var p=candidate.point();var assertions=new LinkedHashMap<>(p.assertions());
        assertions.put("create",List.of(new Assertion("rejection","STATUS_EQUALS",null,422),new Assertion("sideEffect","JSON_EQUALS","$.created",false)));
        var reviewed=new TestPoint(p.id(),p.title(),p.covers(),assertions,p.inputOverrides(),null,p.endAtStepRef(),p.suppressedOutputCalls(),p.generatedFrom());
        confirm(flow.withCalls(flow.nodes(),List.of(reviewed)));
        var ir=planner.generate(flow.flowId(),1,reviewed.id(),List.of());
        assertTrue(ir.steps().getFirst().outputs().isEmpty());assertEquals(0,((Number)ir.steps().getFirst().inputs().get("quantity").value()).intValue());
        assertEquals(1,ir.executionSteps().size());
    }
    @Test void unconfirmedSourceCannotGenerateCandidates() {
        var flow=fixture();when(store.flow(flow.flowId(),1)).thenReturn(new FlowRevision(flow,"hash","DRAFT",null,null));
        assertThrows(IllegalArgumentException.class,()->new CaseCandidateService(store).generate(new CaseCandidateService.Request(flow.flowId(),1,"normal",10,null,null)));
    }
    @Test void statusOnlyCaseCannotBecomeAConfirmedBusinessOracle() {
        var flow=fixture();var p=flow.testPoints().getFirst();
        var statusOnly=new TestPoint(p.id(),p.title(),p.covers(),Map.of("create",List.of(new Assertion("status","STATUS_EQUALS",null,200))),Map.of(),null);
        assertThrows(IllegalArgumentException.class,()->new ScenarioPlanner(store,new IrValidator(new CapabilityRegistry())).validateFlow(flow.withCalls(flow.nodes(),List.of(statusOnly))));
    }
}
