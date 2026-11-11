package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import java.util.*;

class StepMemoryServiceTest {
    StepMemoryRepository repository; StepMemoryService service; Map<String,StepMemory> rows; Flow flow;
    final Map<String,String> contexts = Map.of("first","v1","second","v1");
    @BeforeEach void setup() {
        repository=mock(StepMemoryRepository.class); service=new StepMemoryService(repository); rows=new LinkedHashMap<>();
        when(repository.saveAndFlush(any())).thenAnswer(i->{StepMemory row=i.getArgument(0); rows.put(row.id,row); return row;});
        when(repository.findById(anyString())).thenAnswer(i->Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repository.findTop500ByFlowIdAndEnvironmentAndStateOrderByCreatedAtDesc(anyString(),anyString(),anyString())).thenAnswer(i->rows.values().stream()
            .filter(r->r.flowId.equals(i.getArgument(0))&&r.environment.equals(i.getArgument(1))&&r.state.equals(i.getArgument(2))).sorted(Comparator.comparing((StepMemory r)->r.createdAt).reversed()).toList());
        flow=new FlowContractServiceTest().flow();
        var a=flow.nodes().getFirst(); var b=flow.nodes().get(1);
        flow=new Flow(flow.flowId(),flow.revision(),flow.name(),Map.of(),List.of(new FlowNode("first",a.label(),a.operationRef(),Map.of(),List.of(),List.of(),0),new FlowNode("second",b.label(),b.operationRef(),Map.of(),List.of(),List.of(),0)),
            List.of(new Dependency("first","second","ORDERING_CONSTRAINT","human")),List.of(),List.of(new TestPoint("normal","normal",List.of("first","second"),Map.of(),Map.of(),null)));
    }
    StepMemoryService.Memory memory(){return new StepMemoryService.Memory("当前步骤结论",List.of("保留人规划的路径"),List.of("网关行为待确认"),List.of(),List.of(),List.of(),flow.nodes().getFirst().operationRef());}
    @Test void pendingMemoryIsInvisibleUntilExactCandidateIsConfirmedAndSurvivesNewServiceInstance() {
        var candidate=service.propose(flow,"first","test",contexts,"fixture",memory());
        assertEquals("PENDING",candidate.state()); assertTrue(service.views(flow,"test",contexts).isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.confirm(candidate.id(),flow,"stage",contexts));
        assertThrows(IllegalArgumentException.class,()->service.confirm(candidate.id(),change("first"),"test",contexts));
        service.confirm(candidate.id(),flow,"test",contexts);
        assertEquals("CONFIRMED",new StepMemoryService(repository).views(flow,"test",contexts).getFirst().state());
        assertTrue(service.views(flow,"stage",contexts).isEmpty());
        assertEquals(candidate.id(),service.confirm(candidate.id(),flow,"test",contexts).id());
    }
    Flow change(String id) { return new Flow(flow.flowId(),flow.revision(),flow.name(),flow.initialContext(),flow.nodes().stream().map(n->n.id().equals(id)?new FlowNode(n.id(),n.label(),n.operationRef(),Map.of("body.email",new Binding("literal","changed")),n.outputs(),n.states(),n.waitMillis()):n).toList(),flow.dependencies(),flow.stateModels(),flow.testPoints()); }
    @Test void changingDownstreamKeepsUpstreamMemoryButChangingUpstreamInvalidatesBoth() {
        for(String id:List.of("first","second")){var candidate=service.propose(flow,id,"test",contexts,"fixture",memory());service.confirm(candidate.id(),flow,"test",contexts);}
        var downstream=service.views(change("second"),"test",contexts);
        assertEquals("CONFIRMED",downstream.stream().filter(m->m.nodeId().equals("first")).findFirst().orElseThrow().state());
        assertEquals("STALE",downstream.stream().filter(m->m.nodeId().equals("second")).findFirst().orElseThrow().state());
        assertTrue(service.views(change("first"),"test",contexts).stream().allMatch(m->m.state().equals("STALE")));
        assertTrue(service.views(flow,"test",Map.of("first","background-v2","second","v1")).stream().allMatch(m->m.state().equals("STALE")));
    }
    @Test void sourceRevisionAndDependencyChangesInvalidateMemoryAndBackgroundChangeBlocksConfirmation() {
        var candidate=service.propose(flow,"second","test",contexts,"fixture",memory());
        assertThrows(IllegalArgumentException.class,()->service.confirm(candidate.id(),flow,"test",Map.of("first","v2","second","v1")));
        assertEquals("PENDING",rows.get(candidate.id()).state);
        service.confirm(candidate.id(),flow,"test",contexts);
        var first=flow.nodes().getFirst();
        var changed=new Flow(flow.flowId(),flow.revision(),flow.name(),flow.initialContext(),List.of(new FlowNode(first.id(),first.label(),"login@another-commit",first.inputs(),first.outputs(),first.states(),0),flow.nodes().get(1)),flow.dependencies(),flow.stateModels(),flow.testPoints());
        assertEquals("STALE",service.views(changed,"test",contexts).getFirst().state());
        var parallel=new Flow(flow.flowId(),flow.revision(),flow.name(),flow.initialContext(),flow.nodes(),List.of(),flow.stateModels(),flow.testPoints());
        assertEquals("STALE",service.views(parallel,"test",contexts).getFirst().state());
    }
    @Test void chainCandidatesAreScopedUnconfirmedAndBatchConfirmationValidatesAllBeforeWriting() {
        var first=service.propose(flow,"first","test",contexts,"fixture",memory());
        var second=service.propose(flow,"second","test",contexts,"fixture",memory());
        var ids=List.of(first.id(),second.id());
        assertTrue(service.views(flow,"test",contexts).isEmpty());
        assertTrue(service.working(ids,flow,"test",contexts).stream().allMatch(m->m.state().equals("WORKING")));
        assertThrows(IllegalArgumentException.class,()->service.working(ids,flow,"stage",contexts));
        assertThrows(FlowFailure.class,()->service.confirmChain(ids,change("second"),"test",contexts));
        assertTrue(rows.values().stream().allMatch(r->r.state.equals("PENDING")));
        assertTrue(service.confirmChain(ids,flow,"test",contexts).stream().allMatch(m->m.state().equals("CONFIRMED")));
        assertEquals(2,service.views(flow,"test",contexts).size());
        verify(repository,times(1)).saveAllAndFlush(anyList());
    }
}
