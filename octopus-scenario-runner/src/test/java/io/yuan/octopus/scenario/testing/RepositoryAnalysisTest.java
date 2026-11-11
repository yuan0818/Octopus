package io.yuan.octopus.scenario.testing;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisService.*;
class RepositoryAnalysisTest {
    @Test void oldRequestsCanOmitOptionalRestartEvenWithStrictJacksonNullHandling() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        var r=mapper.readValue("{\"repositoryKey\":\"repo\",\"branch\":\"main\",\"commitId\":\""+"a".repeat(40)+"\",\"environment\":\"test\",\"model\":\"fixture\"}",Request.class);
        assertNull(r.restart());
    }
    Request request(){return new Request("repo:1","master","a".repeat(40),"test","fixture");}
    @Test void persistentLeasesCancellationAndRecoveryFenceOldWorkers() {
        var fixture=new JpaChainWorkspaceStoreTest();String db="repository_"+UUID.randomUUID();String id=id(request());
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);var repository=app.getBean(RepositoryAnalysisRepository.class);
            assertEquals("QUEUED",store.start(request()).state);assertEquals("COLLECTING",store.claim(id,"old").state);
            assertNull(store.claim(id,"duplicate"));assertEquals("COLLECTING",store.start(request()).state);
            assertFalse(repository.findById(id).orElseThrow().snapshotEncrypted.contains("repo:1"));
            store.update(id,null,r->r.state="CANCELLED");assertFalse(store.update(id,"old",r->r.state="READY"));
            store.start(request());store.claim(id,"new");assertFalse(store.update(id,"old",r->r.state="READY"));
            var row=repository.findById(id).orElseThrow();row.leaseUntil=1;repository.saveAndFlush(row);
            store.heartbeat(id,"new");store.recover(id);
            assertEquals("FAILED",store.get(id).state);assertEquals("WORKER_INTERRUPTED",store.get(id).failure.code());
            assertFalse(store.update(id,"new",r->r.state="READY"));
        }
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);assertEquals("FAILED",store.get(id).state);
            assertEquals("QUEUED",store.start(request()).state);assertTrue(store.pending().contains(id));
        }
    }
    @Test void isolatesBranchesAndCommitsButSharesKnowledgeAcrossEnvironments() {
        var r=request();assertNotEquals(id(r),id(new Request(r.repositoryKey(),"release",r.commitId(),r.environment(),r.model())));
        assertNotEquals(id(r),id(new Request(r.repositoryKey(),r.branch(),"b".repeat(40),r.environment(),r.model())));
        assertEquals(id(r),id(new Request(r.repositoryKey(),r.branch(),r.commitId(),"staging",r.model())));
        var source=new RepositoryCatalogClient.SourceEvidence("source-1",1,r.commitId(),"Gateway.java",1,20,"REPOSITORY_CODE","class Gateway {}",false);
        var packet=new RepositoryCatalogClient.BackgroundPacket(r.repositoryKey(),"Platform",r.branch(),r.commitId(),r.environment(),List.of(new RepositoryCatalogClient.Module(1,"gateway","gateway",r.branch(),List.of(r.branch()))),List.of(source),List.of());
        var rule=new Rule("入口签名","SIGNING","签名与 Token 检查独立",List.of("gateway"),List.of("签名需使用运行时密钥"),List.of("source-1"),List.of("部署配置未读取"));
        assertDoesNotThrow(()->validateReport(new Report("仓库入口",List.of(),List.of(rule),List.of()),packet));
        var foreign=new Rule(rule.title(),rule.kind(),rule.description(),List.of("other-repo"),rule.clientPreparation(),rule.evidenceIds(),rule.uncertainties());
        assertThrows(IllegalArgumentException.class,()->validateReport(new Report("仓库入口",List.of(),List.of(foreign),List.of()),packet));
        var invented=new Rule(rule.title(),rule.kind(),rule.description(),rule.modulePaths(),rule.clientPreparation(),List.of("invented"),rule.uncertainties());
        assertThrows(IllegalArgumentException.class,()->validateReport(new Report("仓库入口",List.of(),List.of(invented),List.of()),packet));
    }
    @Test void shortModelReferencesResolveOnlyToEvidenceActuallySupplied() {
        var r=request();var source=new RepositoryCatalogClient.SourceEvidence("real-stable-id",1,r.commitId(),"Gateway.java",1,20,"REPOSITORY_CODE","source",false);
        var packet=new RepositoryCatalogClient.BackgroundPacket(r.repositoryKey(),"Platform",r.branch(),r.commitId(),r.environment(),List.of(new RepositoryCatalogClient.Module(1,"gateway","gateway",r.branch(),List.of(r.branch()))),List.of(source),List.of());
        var input=modelPacket(packet);assertEquals("S1",input.sources().getFirst().id());assertEquals("source",input.sources().getFirst().text());
        var report=new Report("入口规则",List.of(new ModuleRole("gateway","网关",List.of("S1"))),List.of(new Rule("签名","SIGNING","检查签名",List.of("gateway"),List.of(),List.of("S1"),List.of())),List.of());
        validateReport(report,input);var restored=restoreReferences(report,packet);assertEquals(List.of("real-stable-id"),restored.rules().getFirst().evidenceIds());validateReport(restored,packet);
        assertEquals(List.of("real-stable-id"),restored.modules().getFirst().evidenceIds());
        assertThrows(IllegalArgumentException.class,()->validateReport(restored,input));
    }

    @Test void modelQuestionsRequestCredentialReferencesInsteadOfPrivateValues() {
        for(String text:List.of("请提供 tokenSecret 的具体值或获取方式", "请提供 RSA 私钥的具体值", "请提供测试账号的 email 和 password")) {
            String safe=ModelKnowledgePolicy.question(text);assertTrue(safe.contains("运行时引用"));assertFalse(safe.contains("请提供"));
        }
        assertEquals("请确认 MD5 或 SHA256",ModelKnowledgePolicy.question("请确认 MD5 或 SHA256"));
        var report=new Report("summary",List.of(),List.of(new Rule("签名","SIGNING","description",List.of("gateway"),List.of(),List.of("S1"),List.of("tokenSecret 的具体值需获取"))),List.of("请提供私钥的具体值"));
        assertTrue(safeReport(report).questions().getFirst().contains("运行时引用"));assertEquals(report.rules().getFirst().evidenceIds(),safeReport(report).rules().getFirst().evidenceIds());
    }

}
