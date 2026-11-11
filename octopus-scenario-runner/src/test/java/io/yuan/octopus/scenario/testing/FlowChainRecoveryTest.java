package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.FlowChainService.State.*;
import static io.yuan.octopus.scenario.testing.FlowChainService.NodeState.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;

class FlowChainRecoveryTest {
    FlowChainServiceTest fixture; MemoryChainWorkspaceStore store; MutableClock clock;Queue<Runnable> queue;FlowChainService service;
    static class MutableClock extends Clock {
        long current=System.currentTimeMillis();
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(current);}
    }
    @BeforeEach void setup(){fixture=new FlowChainServiceTest();fixture.setup();store=new MemoryChainWorkspaceStore();clock=new MutableClock();queue=new ArrayDeque<>();service=new FlowChainService(fixture.steps,queue::add,store,clock);}
    FlowChainService restart(){return new FlowChainService(fixture.steps,queue::add,store,clock);}
    @Test void restartRetainsCompletedCheckpointsAndReplaysOnlyInterruptedNodeWithPresetValues() {
        var task=service.create(fixture.request);
        doAnswer(i->{var r=(StepSuggestionService.Request)i.getArgument(0);if(r.nodeId().equals("query")){clock.current+=46000;throw new FlowFailure(FlowFailure.Code.MODEL_TIMEOUT);}return fixture.suggestion(r);}).when(fixture.steps).suggest(any(),anyList());
        queue.remove().run();var recovered=restart();recovered.maintenance();var paused=recovered.get(task.id());
        assertEquals(PAUSED,paused.state());assertEquals(COMPLETED,paused.nodes().getFirst().state());assertEquals(INTERRUPTED,paused.nodes().get(1).state());
        assertEquals("WORKER_INTERRUPTED",paused.failure().code());assertEquals("GENERATING",paused.failure().stage());
        assertEquals(task.id(),recovered.latest(fixture.flow.flowId()).id());assertEquals(1,recovered.recent().getFirst().completed());
        doAnswer(i->{var r=(StepSuggestionService.Request)i.getArgument(0);assertEquals("query",r.nodeId());assertEquals("fixture-user",r.contextValues().get("test.user"));assertEquals(List.of("memory-create"),i.getArgument(1));return fixture.suggestion(r);}).when(fixture.steps).suggest(any(),anyList());
        recovered.resume(task.id());queue.remove().run();assertEquals(READY,recovered.get(task.id()).state());assertEquals(2,recovered.get(task.id()).nodes().get(1).attempts());
        assertTrue(recovered.get(task.id()).events().stream().anyMatch(e->e.failure()!=null && e.failure().code().equals("WORKER_INTERRUPTED")));
        verify(fixture.steps,times(3)).suggest(any(),anyList());
    }
    @Test void expiredWorkerCannotWriteLateResultEvenIfAnotherInstanceHasResumedTheTask() {
        var task=service.create(fixture.request);var other=restart();boolean[] expired={false};
        doAnswer(i->{var r=(StepSuggestionService.Request)i.getArgument(0);
            if(!expired[0]){expired[0]=true;clock.current+=46000;other.maintenance();other.resume(task.id());}
            return fixture.suggestion(r);
        }).when(fixture.steps).suggest(any(),anyList());
        queue.remove().run();var checkpoint=other.get(task.id());assertEquals(QUEUED,checkpoint.state());assertTrue(checkpoint.candidate().nodes().getFirst().outputs().isEmpty());
        queue.remove().run();assertEquals(READY,other.get(task.id()).state());assertEquals(2,other.get(task.id()).nodes().getFirst().attempts());
    }
    @Test void liveWorkerHeartbeatPreventsFalseRecoveryAndDuplicateExecutionAcrossInstances() {
        var task=service.create(fixture.request);var other=restart();
        doAnswer(i->{clock.current+=30000;service.maintenance();clock.current+=30000;other.maintenance();assertEquals(RUNNING,other.get(task.id()).state());service.maintenance();return fixture.suggestion(i.getArgument(0));}).when(fixture.steps).suggest(any(),anyList());
        queue.remove().run();assertEquals(READY,service.get(task.id()).state());verify(fixture.steps,times(2)).suggest(any(),anyList());assertTrue(queue.isEmpty());
    }
    @Test void checkpointsSurviveStructuredFailureAndHistorySurvivesSuccessfulRetry() {
        var task=service.create(fixture.request);
        doAnswer(i->{var r=(StepSuggestionService.Request)i.getArgument(0);if(r.nodeId().equals("query"))throw new FlowFailure(FlowFailure.Code.MODEL_AUTH,401,null);return fixture.suggestion(r);}).when(fixture.steps).suggest(any(),anyList());
        queue.remove().run();var failed=service.get(task.id());assertEquals(FlowChainService.State.FAILED,failed.state());assertEquals("MODEL_AUTH",failed.failure().code());assertEquals(401,failed.failure().upstreamStatus());assertFalse(failed.failure().retryable());
        doAnswer(i->fixture.suggestion(i.getArgument(0))).when(fixture.steps).suggest(any(),anyList());var other=restart();other.resume(task.id());queue.remove().run();
        var ready=other.get(task.id());assertEquals(READY,ready.state());assertNull(ready.failure());assertTrue(ready.events().stream().anyMatch(e->e.failure()!=null&&e.failure().code().equals("MODEL_AUTH")));
    }
    @Test void confirmedReceiptSurvivesRestartAndLostResponseWithoutRepeatingConfirmation() {
        var task=service.create(fixture.request);queue.remove().run();var ready=service.get(task.id());var receipt=new FlowChainService.Confirm(ready.originalHash(),ready.resultHash());
        service.confirm(task.id(),receipt);var recovered=restart();assertEquals(ACCEPTED,recovered.latest(fixture.flow.flowId()).state());assertEquals(ACCEPTED,recovered.confirm(task.id(),receipt).state());verify(fixture.steps,times(1)).confirmChain(any(),any(),anyList());
    }
    @Test void cancelledTaskAndRewindCheckpointSurviveRestart() {
        var task=service.create(fixture.request);queue.remove().run();service.retry(task.id(),"query");service.pause(task.id());var other=restart();
        assertEquals(PAUSED,other.get(task.id()).state());assertEquals(COMPLETED,other.get(task.id()).nodes().getFirst().state());assertEquals(WAITING,other.get(task.id()).nodes().get(1).state());
        other.cancel(task.id());assertEquals(CANCELLED,restart().get(task.id()).state());assertTrue(store.get(task.id()).values.isEmpty());
    }
    @Test void unclassifiedExceptionDoesNotLeakSensitiveText() {
        var task=service.create(fixture.request);doThrow(new RuntimeException("password=do-not-serialize-token")).when(fixture.steps).prepare(any());
        queue.remove().run();var failed=service.get(task.id());assertEquals("CONTEXT_UNAVAILABLE",failed.failure().code());assertFalse(canonical(failed).contains("do-not-serialize-token"));
    }
    @Test void unreadableWorkspaceDoesNotBlockRecoveryOfOtherQueuedTasks() {
        var brokenStore=new MemoryChainWorkspaceStore() {
            @Override public synchronized List<String> pending(){var ids=new ArrayList<>(super.pending());ids.addFirst("unreadable");return ids;}
            @Override public synchronized FlowChainService.Run update(String id,java.util.function.Consumer<FlowChainService.Run> change){if(id.equals("unreadable"))throw new FlowFailure(FlowFailure.Code.STORAGE_UNAVAILABLE);return super.update(id,change);}
        };
        var task=brokenStore.create(new FlowChainService.Run(fixture.request));var recovered=new FlowChainService(fixture.steps,queue::add,brokenStore,clock);
        recovered.maintenance();assertEquals(1,queue.size());queue.remove().run();assertEquals(READY,recovered.get(task.id).state());
    }
}
