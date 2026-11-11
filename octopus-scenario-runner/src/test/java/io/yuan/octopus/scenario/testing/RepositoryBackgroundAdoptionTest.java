package io.yuan.octopus.scenario.testing;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisService.*;
class RepositoryBackgroundAdoptionTest {
    Request request(){return new Request("repo:adoption","main","a".repeat(40),null,"fixture",true,"goal");}
    String complete(RepositoryAnalysisStore store, Request request, String summary) {
        var run=store.start(request);store.claim(id(request),"worker");
        store.update(id(request),"worker",r->{r.state="READY";r.report=new Report(summary,List.of(),List.of(),List.of());});
        return RepositoryAnalysisTasks.taskId(run);
    }
    @Test void adoptedReportSurvivesNewFailedAndCompletedTasksAndCanSwitchToHistoricalReport() {
        var fixture=new JpaChainWorkspaceStoreTest();String db="adoption_"+UUID.randomUUID();String first,last;
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);var request=request();String scope=id(request);
            first=complete(store,request,"adopted first");assertNull(store.adopted(scope));
            var chosen=store.adopt(first);assertEquals(first,RepositoryAnalysisTasks.taskId(chosen.run()));
            assertEquals(chosen.adoptedAt(),store.adopt(first).adoptedAt());
            var pending=store.start(request);assertEquals("adopted first",store.adopted(scope).run().report.summary());
            assertThrows(IllegalArgumentException.class,()->store.adopt(RepositoryAnalysisTasks.taskId(pending)));
            store.claim(scope,"worker");store.update(scope,"worker",r->r.state="FAILED");
            assertEquals("adopted first",store.adopted(scope).run().report.summary());
            assertThrows(IllegalArgumentException.class,()->store.adopt(RepositoryAnalysisTasks.taskId(pending)));
            last=complete(store,request,"new unadopted report");
            assertEquals("adopted first",store.adopted(scope).run().report.summary());
            store.migrateVersionScopes();assertEquals(first,RepositoryAnalysisTasks.taskId(store.adopted(scope).run()));
            var service=new RepositoryAnalysisService(store,mock(RepositoryCatalogClient.class),mock(FlowModelClient.class));
            try {
                assertEquals("adopted first",service.reference(request.repositoryKey(),request.branch(),request.commitId(),null).report().summary());
                assertTrue(service.task(first).adopted());assertFalse(service.task(last).adopted());
                service.adoptTask(last);assertEquals("new unadopted report",service.adopted(request).analysis().report().summary());
                service.adoptTask(first);assertEquals("adopted first",service.adopted(request).analysis().report().summary());
            } finally {service.close();}
        }
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);
            assertEquals(first,RepositoryAnalysisTasks.taskId(store.adopted(id(request())).run()));
            assertEquals(last,RepositoryAnalysisTasks.taskId(store.get(id(request()))));
            assertNull(store.adopted(id(new Request("repo:other","main","a".repeat(40),null,""))));
            assertNull(store.adopted(id(new Request("repo:adoption","other","a".repeat(40),null,""))));
            assertNull(store.adopted(id(new Request("repo:adoption","main","b".repeat(40),null,""))));
        }
    }
    @Test void legacyMigrationKeepsLastUsableResultWhenLatestTaskFailed() {
        var fixture=new JpaChainWorkspaceStoreTest();
        try(var app=fixture.open("legacy_adoption_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);var pointers=app.getBean(RepositoryBackgroundAdoptionRepository.class);
            var req=request();var old=complete(store,req,"legacy ready");var newer=store.start(req);
            store.claim(id(req),"worker");store.update(id(req),"worker",r->r.state="FAILED");
            pointers.deleteAll(); // Simulate a pre-upgrade installation, which has no adopted-pointer records.
            store.migrateVersionScopes();store.migrateVersionScopes();
            assertEquals(old,RepositoryAnalysisTasks.taskId(store.adopted(id(req)).run()));
            assertEquals(RepositoryAnalysisTasks.taskId(newer),RepositoryAnalysisTasks.taskId(store.get(id(req))));
            assertEquals(2,store.summaries().size());
        }
    }
    @Test void noAdoptionRemainsEmptyAcrossRestartEvenIfAnalysisCompletes() {
        var fixture=new JpaChainWorkspaceStoreTest();String db="empty_adoption_"+UUID.randomUUID();
        try(var app=fixture.open(db)){complete(app.getBean(RepositoryAnalysisStore.class),request(),"not selected");}
        try(var app=fixture.open(db)){assertNull(app.getBean(RepositoryAnalysisStore.class).adopted(id(request())));}
    }
}
