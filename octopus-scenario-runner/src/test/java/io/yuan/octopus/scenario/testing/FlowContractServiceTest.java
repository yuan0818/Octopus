package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;

class FlowContractServiceTest {
    final RepositoryCatalogClient catalog=mock(RepositoryCatalogClient.class);
    final BusinessKnowledgeStore store=mock(BusinessKnowledgeStore.class);
    final IrValidator validator=mock(IrValidator.class);
    final FlowContractService service=new FlowContractService(catalog,store,validator);
    final Implementation impl=new Implementation(51,"master","a".repeat(40),"app.Api.login():app.Result");
    final Operation old=new Operation("login","code_old","app","auth","POST","/login",Map.of(),null,null,List.of(impl),new Source("ANALYZED_CODE","sig","hash"),List.of("http","json"),null);
    Flow flow() { return new Flow("flow",7,"登录后读取",Map.of(),List.of(new FlowNode("first","登录",old.ref(),Map.of(),List.of(),List.of(),0),new FlowNode("again","再次登录",old.ref(),Map.of(),List.of(),List.of(),0)),
            List.of(new Dependency("first","again","ORDERING_CONSTRAINT","human")),List.of(),List.of(new TestPoint("normal","正常路径",List.of("first","again"),Map.of("first",List.of(new Assertion("status","STATUS_EQUALS",null,200))),Map.of(),null))); }
    RepositoryCatalogClient.Contract contract(String commit) { return new RepositoryCatalogClient.Contract(51,"master",commit,impl.signature(),"POST","/login",Map.of("body.email",new Port("body","email","string",true,null)),
            Map.of("type","object","properties",Map.of("email",Map.of("type","string"))),Map.of("200",Map.of("type","object","properties",Map.of("token",Map.of("type","string")))),List.of()); }
    @Test void enrichesExistingReferencesWithoutChangingOrderOrSavingTheHumanFlow() {
        when(store.operation(old.ref())).thenReturn(old); when(store.saveOperation(any())).thenAnswer(i->i.getArgument(0));
        when(catalog.contract(impl,"POST","/login")).thenReturn(contract(impl.commitId()));
        var original=flow(); var result=service.prepare(original); var next=result.operations().getFirst();
        assertEquals(old.operationId(),next.operationId()); assertNotEquals(old.revision(),next.revision());
        assertEquals(old.implementations(),next.implementations()); assertNotNull(next.responseSchema()); assertTrue(next.inputs().containsKey("body.email"));
        assertEquals(original.dependencies(),result.flow().dependencies()); assertEquals(original.testPoints(),result.flow().testPoints());
        assertEquals(original.revision(),result.flow().revision()); assertEquals(List.of("first","again"),result.flow().nodes().stream().map(FlowNode::id).toList());
        assertTrue(result.flow().nodes().stream().allMatch(n->n.operationRef().equals(next.ref()))); assertEquals(old.ref(),original.nodes().getFirst().operationRef());
        verify(catalog,times(1)).contract(impl,"POST","/login"); verify(store,never()).saveDraft(any());
        // The same enriched contracts, including the new input, must reach the model.
        when(store.operation(next.ref())).thenReturn(next); when(catalog.scopes()).thenReturn(List.of());
        var llm=mock(FlowModelClient.class); when(llm.suggest(any(),any(),any(),any(),any())).thenReturn(new FlowModelClient.Response("{\"nodes\":[{\"id\":\"first\",\"inputs\":{},\"outputs\":[]},{\"id\":\"again\",\"inputs\":{},\"outputs\":[]}],\"assertions\":[],\"questions\":[],\"assumptions\":[]}","fixture"));
        new FlowSuggestionService(catalog,mock(ProjectContextService.class),store,llm).suggest(new FlowSuggestionService.Request(result.flow(),"test","登录",null,Map.of(),Map.of("first",List.of("body.email"))));
        var facts=ArgumentCaptor.forClass(List.class); verify(llm).suggest(any(),any(),any(),facts.capture(),any());
        assertEquals(next.inputs(),((Map<?,?>)facts.getValue().getFirst()).get("inputs"));
        assertEquals(next.responseSchema(),((Map<?,?>)facts.getValue().getFirst()).get("responseSchema"));
    }
    @Test void rejectsAContractFromAnotherCommitBeforePersisting() {
        when(store.operation(old.ref())).thenReturn(old); when(catalog.contract(impl,"POST","/login")).thenReturn(contract("b".repeat(40)));
        assertThrows(IllegalArgumentException.class,()->service.prepare(flow())); verify(store,never()).saveOperation(any());
    }
    @Test void preparingOneNodeDoesNotHydrateAnotherUseOfTheSameApi() {
        when(store.operation(old.ref())).thenReturn(old); when(store.saveOperation(any())).thenAnswer(i->i.getArgument(0));
        when(catalog.contract(impl,"POST","/login")).thenReturn(contract(impl.commitId()));
        var result=service.prepare(flow(),"first");
        assertNotEquals(old.ref(),result.flow().nodes().getFirst().operationRef());
        assertEquals(flow().nodes().get(1),result.flow().nodes().get(1));
        assertEquals(2,result.operations().size()); verify(catalog,times(1)).contract(impl,"POST","/login");
    }
}
