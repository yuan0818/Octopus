package io.yuan.octopus.scenario.testing;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisService.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
class RepositoryAnalysisHistoryTest {
    @Test void migratesEnvironmentScopesWithoutLosingHistoricalReportsOrTaskLinks() throws Exception {
        var fixture=new JpaChainWorkspaceStoreTest();String db="version_migration_"+UUID.randomUUID(),chosenId,oldId;
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);var repository=app.getBean(RepositoryAnalysisRepository.class);var cipher=app.getBean(ContextCredentialCipher.class);
            var qa=new Run(request(false,"qa-old"));qa.taskId=null;qa.state="READY";qa.createdAt=qa.updatedAt="2026-09-01T00:00:00Z";qa.report=new Report("older-report",List.of(),List.of(),List.of());
            var test=new Run(new Request(qa.request.repositoryKey(),"main",qa.request.commitId(),"test","fixture",false,"newer"));test.taskId=null;test.state="READY";test.createdAt=test.updatedAt="2026-09-02T00:00:00Z";test.report=new Report("newer-report",List.of(),List.of(),List.of());
            oldId=RepositoryAnalysisTasks.taskId(qa);chosenId=RepositoryAnalysisTasks.taskId(test);
            for(var run:List.of(qa,test)){var row=new RepositoryAnalysisWorkspace();row.id=hash(List.of(run.request.repositoryKey(),run.request.branch(),run.request.commitId(),run.request.environment()));row.state=run.state;row.updatedAt=java.time.Instant.parse(run.updatedAt);row.snapshotEncrypted=cipher.encrypt(canonical(run));repository.saveAndFlush(row);}
            store.migrateVersionScopes();store.migrateVersionScopes();
            assertEquals(1,repository.count());assertEquals(2,store.summaries().size());assertEquals(1,store.summaries().stream().filter(RepositoryAnalysisTasks.Summary::current).count());
            assertEquals("newer-report",store.get(id(qa.request)).report.summary());assertEquals(chosenId,RepositoryAnalysisTasks.taskId(store.get(id(qa.request))));assertFalse(store.task(oldId).current());assertEquals("older-report",store.task(oldId).run().report.summary());
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();assertFalse(mapper.writeValueAsString(test.request).contains("environment"));assertFalse(mapper.writeValueAsString(store.summaries()).contains("environment"));
        }
        try(var app=fixture.open(db)){var store=app.getBean(RepositoryAnalysisStore.class);assertTrue(store.task(chosenId).current());assertFalse(store.task(oldId).current());}
    }
    Request request(boolean restart,String goal){return new Request("repo:history","main","a".repeat(40),"qa","fixture",restart,goal);}
    @Test void freshPlansArchiveSnapshotsWhileRetryKeepsTaskIdentityAndRejectsStaleActions() {
        var fixture=new JpaChainWorkspaceStoreTest();String db="history_"+UUID.randomUUID();String firstId,secondId,oldSnapshot;
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);var history=app.getBean(RepositoryAnalysisHistoryRepository.class);
            var first=store.start(request(false,"first-goal"));firstId=RepositoryAnalysisTasks.taskId(first);String scope=id(first.request);
            store.claim(scope,"owner");store.update(scope,"owner",r->{r.state="FAILED";r.failure=failure(new FlowFailure(FlowFailure.Code.MODEL_TIMEOUT),"REPOSITORY_MODULE");});
            var resumed=store.startExpected(request(false,"first-goal"),firstId);assertEquals(firstId,RepositoryAnalysisTasks.taskId(resumed));assertEquals(1,store.summaries().size());
            store.claim(scope,"second-worker");store.update(scope,"second-worker",r->{r.state="READY";r.report=new Report("first-report",List.of(),List.of(),List.of());});
            oldSnapshot=canonical(store.task(firstId).run());
            var second=store.start(request(true,"second-goal"));secondId=RepositoryAnalysisTasks.taskId(second);
            assertNotEquals(firstId,secondId);assertEquals(2,store.summaries().size());assertFalse(store.task(firstId).current());assertTrue(store.task(secondId).current());
            assertEquals(oldSnapshot,canonical(store.task(firstId).run()));assertThrows(IllegalArgumentException.class,()->store.startExpected(request(false,"first-goal"),firstId));
            assertFalse(store.update(scope,"second-worker",r->r.state="READY"));
            var old=history.findById(firstId).orElseThrow();assertFalse(old.snapshotEncrypted.contains("first-report"));assertFalse(old.summaryEncrypted.contains("first-goal"));
            assertFalse(canonical(store.summaries()).contains("first-report"));
            var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);
            var service=new RepositoryAnalysisService(store,catalog,llm);try {
                assertThrows(IllegalArgumentException.class,()->service.cancelTask(firstId));assertThrows(IllegalArgumentException.class,()->service.resumeTask(firstId));
                assertEquals(2,service.tasks("repo:history",null,"main",null,"qa",null,null,1,20).total());
                assertEquals(1,service.tasks(null,null,null,null,null,"READY","first-goal",1,20).total());
                assertEquals(2,service.tasks(null,null,null,null,"other",null,null,1,20).total());
            } finally {service.close();}
        }
        try(var app=fixture.open(db)) {var store=app.getBean(RepositoryAnalysisStore.class);assertEquals(oldSnapshot,canonical(store.task(firstId).run()));assertTrue(store.task(secondId).current());}
    }
    @Test void legacySnapshotsAppearWithoutFabricatingMissingHistoryAndAreArchivedOnReplan() {
        var fixture=new JpaChainWorkspaceStoreTest();
        try(var app=fixture.open("history_legacy_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);var repository=app.getBean(RepositoryAnalysisRepository.class);var history=app.getBean(RepositoryAnalysisHistoryRepository.class);var cipher=app.getBean(ContextCredentialCipher.class);
            var run=new Run(request(false,"legacy-goal"));run.taskId=null;run.state="READY";
            var row=new RepositoryAnalysisWorkspace();row.id=id(run.request);row.state=run.state;row.updatedAt=java.time.Instant.parse(run.updatedAt);row.snapshotEncrypted=cipher.encrypt(canonical(run));repository.saveAndFlush(row);
            String legacyId=RepositoryAnalysisTasks.taskId(run);
            assertEquals(0,history.count());assertEquals(1,store.summaries().size());assertEquals(legacyId,store.summaries().getFirst().taskId());assertTrue(store.task(legacyId).current());
            store.start(request(true,"new-goal"));assertEquals(2,store.summaries().size());assertFalse(store.task(legacyId).current());assertEquals("legacy-goal",store.task(legacyId).run().request.goal());
        }
    }
}
