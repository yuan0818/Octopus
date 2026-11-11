package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.FlowChainService.State.*;
import static io.yuan.octopus.scenario.testing.FlowChainService.NodeState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;

class FlowChainServiceTest {
    StepSuggestionService steps; FlowChainService service; Queue<Runnable> queue; Flow flow; Operation operation;
    List<String> calls; FlowChainService.Request request;
    @BeforeEach void setup() {
        var fixture=new FlowSuggestionServiceTest();fixture.setup();flow=fixture.flow;operation=fixture.operation;
        steps=mock(StepSuggestionService.class);queue=new ArrayDeque<>();service=new FlowChainService(steps,queue::add,new MemoryChainWorkspaceStore(),java.time.Clock.systemUTC());calls=new ArrayList<>();
        request=new FlowChainService.Request("request_abcdefghijkl",flow,"test","按已规划路径创建后查询","fixture",Map.of("test.user","fixture-user"));
        when(steps.prepare(any())).thenAnswer(i->{var r=(StepSuggestionService.Prepare)i.getArgument(0);calls.add("prepare:"+r.nodeId());return prepared(r.flow(),r.nodeId());});
        doAnswer(i->{var r=(StepSuggestionService.Request)i.getArgument(0);calls.add("generate:"+r.nodeId());return suggestion(r);}).when(steps).suggest(any(),anyList());
        when(steps.confirmChain(any(),any(),anyList())).thenAnswer(i->List.of(memory("create","CONFIRMED"),memory("query","CONFIRMED")));
    }
    StepSuggestionService.Prepared prepared(Flow flow,String id){return new StepSuggestionService.Prepared(flow,List.of(operation),List.of(),id,List.of(),List.of(),List.of(),id.equals("query")?List.of("create"):List.of());}
    StepMemoryService.View memory(String id,String state){return new StepMemoryService.View("memory-"+id,id,state,"fixture","now",new StepMemoryService.Memory("本步骤用途",List.of(),List.of("入口规则待确认"),List.of(),List.of(),List.of(),operation.ref()));}
    StepSuggestionService.Suggestion suggestion(StepSuggestionService.Request r) {
        var nodes=r.flow().nodes().stream().map(n->n.id().equals(r.nodeId()) ? new FlowNode(n.id(),n.label(),n.operationRef(),Map.of("userId",new Binding("context",n.id().equals("create")?"test.user":"order.id")),
            n.id().equals("create")?List.of(new Output("order.id","$.body.id","string",false)):n.outputs(),n.states(),n.waitMillis()):n).toList();
        var result=new Flow(r.flow().flowId(),r.flow().revision(),r.flow().name(),r.flow().initialContext(),nodes,r.flow().dependencies(),r.flow().stateModels(),r.flow().testPoints());
        return new StepSuggestionService.Suggestion(result,List.of(operation),"fixture",List.of(),List.of(),List.of(),hash(r.flow()),r.nodeId(),memory(r.nodeId(),"PENDING"));
    }
    void work(){queue.remove().run();}
    @Test void automaticallyAdvancesTheWholeChainAndPassesOnlyServerOwnedCandidatesWithoutConfirming() {
        var task=service.create(request);assertEquals(QUEUED,task.state());assertTrue(task.nodes().stream().allMatch(n->n.state()==WAITING));
        work();var ready=service.get(task.id());assertEquals(READY,ready.state());assertEquals(List.of("prepare:create","generate:create","prepare:query","generate:query"),calls);
        assertTrue(ready.nodes().stream().allMatch(n->n.state()==COMPLETED));assertTrue(ready.revision()>task.revision());
        assertEquals(canonical(flow),canonical(ready.original()));assertEquals("order.id",ready.candidate().nodes().get(1).inputs().get("userId").value());
        var requests=ArgumentCaptor.forClass(StepSuggestionService.Request.class);var ids=ArgumentCaptor.forClass(List.class);
        verify(steps,times(2)).suggest(requests.capture(),ids.capture());assertEquals(List.of(),ids.getAllValues().get(0));assertEquals(List.of("memory-create"),ids.getAllValues().get(1));
        assertEquals("order.id",requests.getAllValues().get(1).flow().nodes().getFirst().outputs().getFirst().key());
        verify(steps,never()).confirmChain(any(),any(),any());
        assertFalse(canonical(ready).contains("fixture-user"),"preset values are not returned in task snapshots");
    }
    @Test void pauseDuringGenerationFinishesCurrentNodeAndResumeStartsOnlyRemainingNode() {
        var task=service.create(request);
        doAnswer(i->{var r=(StepSuggestionService.Request)i.getArgument(0);calls.add("generate:"+r.nodeId());if(r.nodeId().equals("create"))assertEquals(PAUSE_REQUESTED,service.pause(task.id()).state());return suggestion(r);}).when(steps).suggest(any(),anyList());
        work();assertEquals(PAUSED,service.get(task.id()).state());assertEquals(List.of("prepare:create","generate:create"),calls);
        assertEquals(COMPLETED,service.get(task.id()).nodes().getFirst().state());
        service.resume(task.id());assertThrows(IllegalArgumentException.class,()->service.resume(task.id()));work();
        assertEquals(READY,service.get(task.id()).state());assertEquals(1,calls.stream().filter(s->s.equals("generate:create")).count());
    }
    @Test void failuresKeepEarlierCandidatesAndRetryContinuesFromFailedNode() {
        var task=service.create(request);boolean[] failed={false};
        doAnswer(i->{var r=(StepSuggestionService.Request)i.getArgument(0);calls.add("generate:"+r.nodeId());if(r.nodeId().equals("query")&&!failed[0]){failed[0]=true;throw new IllegalStateException("fixture unavailable");}return suggestion(r);}).when(steps).suggest(any(),anyList());
        work();var stopped=service.get(task.id());assertEquals(FlowChainService.State.FAILED,stopped.state());assertEquals(COMPLETED,stopped.nodes().getFirst().state());
        assertEquals(FlowChainService.NodeState.FAILED,stopped.nodes().get(1).state());assertEquals("order.id",stopped.candidate().nodes().getFirst().outputs().getFirst().key());
        service.resume(task.id());work();assertEquals(READY,service.get(task.id()).state());assertEquals(2,service.get(task.id()).nodes().get(1).attempts());
        assertEquals(1,calls.stream().filter(s->s.equals("generate:create")).count());
    }
    @Test void retryFromEarlierNodeRestoresCheckpointAndDiscardsAllLaterCandidateMemories() {
        var task=service.create(request);work();calls.clear();
        var rewind=service.retry(task.id(),"create");assertEquals(canonical(flow),canonical(rewind.candidate()));assertTrue(rewind.nodes().stream().allMatch(n->n.memory()==null));
        work();var requests=ArgumentCaptor.forClass(StepSuggestionService.Request.class);var ids=ArgumentCaptor.forClass(List.class);verify(steps,times(4)).suggest(requests.capture(),ids.capture());
        assertEquals(List.of(),ids.getAllValues().get(2));assertTrue(requests.getAllValues().get(2).flow().nodes().getFirst().outputs().isEmpty());
        assertEquals(READY,service.get(task.id()).state());assertEquals(2,service.get(task.id()).nodes().getFirst().attempts());
    }
    @Test void cancelDuringAnInflightModelCallDiscardsTheLateResponseAndNeverStartsNextNode() {
        var task=service.create(request);
        doAnswer(i->{service.cancel(task.id());return suggestion(i.getArgument(0));}).when(steps).suggest(any(),anyList());
        work();var cancelled=service.get(task.id());assertEquals(CANCELLED,cancelled.state());assertEquals(canonical(flow),canonical(cancelled.candidate()));
        verify(steps,times(1)).prepare(any());assertTrue(cancelled.nodes().stream().allMatch(n->n.memory()==null));
        assertThrows(IllegalArgumentException.class,()->service.resume(task.id()));
    }
    @Test void creationIsIdempotentAndASecondActiveTaskForTheSameFlowIsRejected() {
        var task=service.create(request);assertEquals(task.id(),service.create(request).id());assertEquals(1,queue.size());
        var other=new FlowChainService.Request("request_another_1234",flow,"test","goal","fixture",Map.of());
        assertThrows(IllegalArgumentException.class,()->service.create(other));
        service.cancel(task.id());assertNotEquals(task.id(),service.create(other).id());
    }
    @Test void queuedPauseResumeCannotLaunchConcurrentWorkersForTheSameRun() {
        var task=service.create(request);service.pause(task.id());service.resume(task.id());assertEquals(1,queue.size());
        work();assertEquals(READY,service.get(task.id()).state());verify(steps,times(2)).suggest(any(),anyList());
    }
    @Test void onlyCompleteReviewedSnapshotCanConfirmAllMemoriesAndConfirmationIsIdempotent() {
        var task=service.create(request);assertThrows(IllegalArgumentException.class,()->service.confirm(task.id(),new FlowChainService.Confirm(task.originalHash(),task.resultHash())));
        work();var ready=service.get(task.id());assertThrows(IllegalArgumentException.class,()->service.confirm(task.id(),new FlowChainService.Confirm(ready.originalHash(),"different")));
        var receipt=new FlowChainService.Confirm(ready.originalHash(),ready.resultHash());assertEquals(ACCEPTED,service.confirm(task.id(),receipt).state());assertEquals(ACCEPTED,service.confirm(task.id(),receipt).state());
        verify(steps,times(1)).confirmChain(eq(ready.candidate()),eq("test"),eq(List.of("memory-create","memory-query")));
        assertThrows(IllegalArgumentException.class,()->service.retry(task.id(),"create"));
    }
    @Test void backgroundConflictLeavesResultsReviewableWithoutClaimingTheyWereApplied() {
        var task=service.create(request);work();var ready=service.get(task.id());
        when(steps.confirmChain(any(),any(),anyList())).thenThrow(new IllegalArgumentException("候选记忆已过期"));
        assertThrows(FlowFailure.class,()->service.confirm(task.id(),new FlowChainService.Confirm(ready.originalHash(),ready.resultHash())));
        assertEquals(READY,service.get(task.id()).state());assertTrue(service.get(task.id()).error().contains("过期"));
    }
    @Test void traversalFollowsDependenciesRatherThanChangingTheHumanPresentationOrder() {
        var reversed=new Flow(flow.flowId(),flow.revision(),flow.name(),flow.initialContext(),List.of(flow.nodes().get(1),flow.nodes().getFirst()),flow.dependencies(),flow.stateModels(),flow.testPoints());
        assertEquals(List.of("create","query"),FlowChainService.ordered(reversed).stream().map(FlowNode::id).toList());assertEquals("query",reversed.nodes().getFirst().id());
    }
}
