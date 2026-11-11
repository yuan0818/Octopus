package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import java.util.*;
import java.util.concurrent.*;

class JpaChainWorkspaceStoreTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @EntityScan(basePackageClasses=FlowChainWorkspace.class)
    @EnableJpaRepositories(basePackageClasses=FlowChainWorkspaceRepository.class)
    @Import({JpaChainWorkspaceStore.class,ContextCredentialCipher.class,StepMemoryService.class,RepositoryAnalysisStore.class})
    static class Config {}
    ConfigurableApplicationContext open(String name) {
        return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
            "--spring.datasource.url=jdbc:h2:mem:"+name+";MODE=MySQL;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver","--spring.datasource.username=sa","--spring.datasource.password=",
            "--spring.jpa.hibernate.ddl-auto=update","--spring.sql.init.mode=never",
            "--scenario.testing.encryption-key=0123456789abcdef0123456789abcdef",
            "--logging.level.root=ERROR","--spring.main.banner-mode=off");
    }
    FlowChainService.Run run(String requestId) {
        var f=new FlowChainServiceTest();f.setup();return new FlowChainService.Run(new FlowChainService.Request(requestId,f.flow,"test","恢复验收","fixture",Map.of("test.user","fixture-private-preset")));
    }
    @Test void encryptedCheckpointsAndIdempotencySurviveRealApplicationContextRestart() {
        String db="chain_"+UUID.randomUUID();String id;
        try(var app=open(db)) {
            var store=app.getBean(JpaChainWorkspaceStore.class);var original=run("request_persist_123456");id=store.create(original).id;
            store.update(id,r->{r.nodes.getFirst().before=r.candidate;r.nodes.getFirst().attempts=2;r.state(FlowChainService.State.PAUSED,"paused checkpoint");});
            var row=app.getBean(FlowChainWorkspaceRepository.class).findById(id).orElseThrow();assertFalse(row.snapshotEncrypted.contains("fixture-private-preset"));assertFalse(row.snapshotEncrypted.contains("contextValues"));
        }
        try(var app=open(db)) {
            var store=app.getBean(JpaChainWorkspaceStore.class);var restored=store.get(id);assertEquals(FlowChainService.State.PAUSED,restored.state);assertEquals("fixture-private-preset",restored.values.get("test.user"));
            assertEquals(2,restored.nodes.getFirst().attempts);assertNotNull(restored.nodes.getFirst().before);assertEquals(id,store.create(run("request_persist_123456")).id);
            assertEquals(id,store.latest(restored.request.flow().flowId()).id);assertEquals(id,store.recent().getFirst().id());
            assertThrows(IllegalArgumentException.class,()->store.create(run("request_conflict_123456")));
        }
    }
    @Test void memoryConfirmationAndAcceptedCheckpointRollbackTogether() {
        try(var app=open("atomic_"+UUID.randomUUID())) {
            var store=app.getBean(JpaChainWorkspaceStore.class);var memories=app.getBean(StepMemoryService.class);var repository=app.getBean(StepMemoryRepository.class);
            var task=store.create(run("request_atomic_123456"));var node=task.candidate.nodes().getFirst();var contexts=Map.<String,String>of();
            var draft=new StepMemoryService.Memory("fixture",List.of(),List.of(),List.of(),List.of(),List.of(),node.operationRef());
            var proposal=memories.propose(task.candidate,node.id(),"test",contexts,"fixture",draft);
            assertThrows(IllegalStateException.class,()->store.update(task.id,r->{memories.confirmChain(List.of(proposal.id()),r.candidate,"test",contexts);r.state(FlowChainService.State.ACCEPTED,"confirmed");throw new IllegalStateException("simulate snapshot write failure");}));
            assertEquals("PENDING",repository.findById(proposal.id()).orElseThrow().state);assertEquals(FlowChainService.State.QUEUED,store.get(task.id).state);
            store.update(task.id,r->{memories.confirmChain(List.of(proposal.id()),r.candidate,"test",contexts);r.state(FlowChainService.State.ACCEPTED,"confirmed");});
            assertEquals("CONFIRMED",repository.findById(proposal.id()).orElseThrow().state);assertEquals(FlowChainService.State.ACCEPTED,store.get(task.id).state);
        }
    }
    @Test void rowLockSerializesConcurrentChangesAndUniqueRequestSurvivesConcurrentPosts() throws Exception {
        try(var app=open("concurrent_"+UUID.randomUUID());var threads=Executors.newFixedThreadPool(2)) {
            var store=app.getBean(JpaChainWorkspaceStore.class);var gate=new CountDownLatch(1);
            Callable<String> create=()->{gate.await();return store.create(run("request_parallel_123456")).id;};
            var a=threads.submit(create);var b=threads.submit(create);gate.countDown();String id=a.get(20,TimeUnit.SECONDS);assertEquals(id,b.get(20,TimeUnit.SECONDS));
            var before=store.get(id).revision;
            var updates=new ArrayList<Future<?>>();for(int i=0;i<8;i++)updates.add(threads.submit(()->store.update(id,r->r.event(null,"TEST","concurrent event",null))));
            for(var result:updates)result.get(20,TimeUnit.SECONDS);assertEquals(before+8,store.get(id).revision);
        }
    }
    @Test void oneCorruptSnapshotRemainsVisibleWithoutHidingHealthyRecoverableTasks() {
        try(var app=open("corrupt_"+UUID.randomUUID())) {
            var store=app.getBean(JpaChainWorkspaceStore.class);var repository=app.getBean(FlowChainWorkspaceRepository.class);
            var old=store.create(run("request_corrupt_123456"));store.update(old.id,r->r.state(FlowChainService.State.ACCEPTED,"fixture terminal"));
            var healthy=store.create(run("request_healthy_123456"));var row=repository.findById(old.id).orElseThrow();row.snapshotEncrypted="corrupt-fixture";repository.saveAndFlush(row);
            assertThrows(FlowFailure.class,()->store.get(old.id));assertEquals(2,store.recent().size());assertTrue(store.recent().stream().anyMatch(t->t.id().equals(healthy.id)&&t.failure()==null));
            assertTrue(store.recent().stream().anyMatch(t->t.id().equals(old.id)&&t.failure().code().equals("STORAGE_UNAVAILABLE")));
            assertEquals(2,store.tasks().size());
            assertTrue(store.tasks().stream().anyMatch(t->t.id().equals(old.id)&&t.failure().code().equals("STORAGE_UNAVAILABLE")));
        }
    }
    @Test void taskCenterIncludesCancelledHistoryAndCurrentNodeWithoutExposingSnapshots() {
        try(var app=open("task_center_"+UUID.randomUUID())) {
            var store=app.getBean(JpaChainWorkspaceStore.class);
            var cancelled=store.create(run("request_cancelled_123456"));
            store.update(cancelled.id,r->r.state(FlowChainService.State.CANCELLED,"cancelled"));
            var active=store.create(run("request_active_123456"));
            store.update(active.id,r->{r.nodes.getFirst().state=FlowChainService.NodeState.COMPLETED;r.nodes.get(1).state=FlowChainService.NodeState.GENERATING;r.state(FlowChainService.State.RUNNING,"second node");});
            var list=store.tasks();assertEquals(2,list.size());assertEquals(active.id,list.getFirst().id());
            var summary=list.getFirst();assertEquals(1,summary.completed());assertEquals(2,summary.total());
            assertEquals("query",summary.currentNodeId());assertEquals(FlowChainService.NodeState.GENERATING,summary.currentNodeState());
            assertEquals(active.request.flow().nodes().get(1).label(),summary.currentNodeLabel());assertEquals(active.createdAt,summary.createdAt());assertEquals("fixture",summary.model());
            assertEquals(FlowChainService.State.CANCELLED,list.getLast().state());assertEquals(1,store.recent().size());
            String json=canonical(list);assertFalse(json.contains("fixture-private-preset"));assertFalse(json.contains("initialContext"));assertFalse(json.contains("candidate"));
        }
    }
}
