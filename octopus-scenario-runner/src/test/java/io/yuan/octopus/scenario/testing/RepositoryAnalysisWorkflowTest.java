package io.yuan.octopus.scenario.testing;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisService.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisWorkflow.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

class RepositoryAnalysisWorkflowTest {
    final Request request=new Request("repo:orchestration","main","a".repeat(40),"test","fixture",false,"查明跨模块签名和加密上下文");
    final RepositoryCatalogClient.Inventory inventory=new RepositoryCatalogClient.Inventory(request.repositoryKey(),"Example",request.branch(),request.commitId(),request.environment(),
        List.of(new RepositoryCatalogClient.Module(1,"billing","billing","main",List.of("main")),new RepositoryCatalogClient.Module(2,"identity","identity","main",List.of("main"))),
        List.of("README.md","billing/Billing.java","identity/Identity.java"),List.of());
    Plan plan(){return new Plan("先理解身份，再理解账单，最后汇总",List.of(new Task("identity","身份模块",List.of("identity"),"识别身份边界",List.of()),new Task("billing","账单模块",List.of("billing"),"检查如何关联身份",List.of("identity"))));}
    Report memory(String module,String id){return new Report(module+" 的职责和边界",List.of(new ModuleRole(module,"负责 "+module,List.of(id))),List.of(new Rule("业务约束","BUSINESS","保留业务约束",List.of(module),List.of(),List.of(id),List.of("部署配置未知"))),List.of("需确认部署配置"));}
    FlowModelClient.Response response(Object body){return new FlowModelClient.Response(canonical(body),"fixture");}
    @Test void everyPlannedTaskRequiresConfigurationInvestigationWithoutEnvironmentScope() {
        var first=plan().tasks().getFirst();var missing=new Task(first.id(),first.title(),first.modulePaths(),first.objective(),first.dependsOn(),null);
        assertThrows(ReportValidationException.class,()->validatePlan(new Plan("missing configuration scope",List.of(missing,plan().tasks().get(1))),inventory));
        assertDoesNotThrow(()->validatePlan(plan(),inventory));
        var overview=overview(inventory);assertFalse(overview.containsKey("environment"));assertNotNull(overview.get("requiredTopics"));assertNotNull(overview.get("configurationFiles"));
    }
    private FlowModelClient.Response unknownStage(Map<String,Object> context) {
        if(context.get("stage").equals("ANALYZE")) {
            var task=(Task)context.get("task");
            return response(new Decision("DONE",null,null,null,null,new Report("缺少直接证据",task.modulePaths().stream().map(m->new ModuleRole(m,"职责未知",List.<String>of())).toList(),List.of(),List.of("需继续取证"))));
        }
        assertEquals("SYNTHESIZE",context.get("stage"));assertEquals("",context.get("previousFailure"));
        return response(new Synthesis(List.of("identity","billing"),new Report("缺少直接证据",inventory.modules().stream().map(m->new ModuleRole(m.modulePath(),"职责未知",List.<String>of())).toList(),List.of(),List.of("需继续取证"))));
    }
    @Test void planningCorrectsUnknownAndMissingModulesBeforeAnyTaskIsSaved() {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);var calls=new AtomicInteger();String id=id(request);
        try(var app=fixture.open("plan_module_repairs_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"worker");store.update(id,"worker",r->r.inventory=inventory);
            when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{Map<String,Object> c=inv.getArgument(1);
                if(!c.get("stage").equals("PLAN"))return unknownStage(c);
                assertNull(store.get(id).plan);assertTrue(store.get(id).tasks==null || store.get(id).tasks.isEmpty());
                assertEquals(List.of("billing","identity"),c.get("allowedModulePaths"));assertNotNull(c.get("planConstraints"));
                int n=calls.incrementAndGet();
                if(n==1)return response(new Plan("invalid",List.of(new Task("first","title",List.of("not-a-module/private-value"),"objective",List.of()))));
                if(n==2){assertTrue(c.get("previousFailure").toString().contains("tasks[0].modulePaths[0]"));assertFalse(canonical(c).contains("private-value"));return response(new Plan("missing",List.of(plan().tasks().getFirst())));}
                assertTrue(c.get("previousFailure").toString().contains("遗漏 1 个模块"));return response(plan());
            });
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"worker");
            assertEquals("READY",store.get(id).state,()->canonical(store.get(id).failure));assertEquals(3,calls.get());verifyNoInteractions(catalog);
        }
    }
    @Test void persistentPlanningFailureIsBoundedAndResumesTheSameTaskWithoutInvalidPlan() {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);var calls=new AtomicInteger();String id=id(request);
        try(var app=fixture.open("plan_module_retry_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"first");store.update(id,"first",r->r.inventory=inventory);
            String taskId=store.get(id).taskId;
            when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{Map<String,Object> c=inv.getArgument(1);
                if(!c.get("stage").equals("PLAN"))return unknownStage(c);
                if(calls.incrementAndGet()<=3)return response(new Plan("invalid",List.of(new Task("first","title",List.of("private-value"),"objective",List.of()))));
                return response(plan());
            });
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"first");
            var failed=store.get(id);assertEquals("FAILED",failed.state);assertNull(failed.plan);assertTrue(failed.tasks==null || failed.tasks.isEmpty());assertEquals(3,calls.get());
            assertTrue(failed.failure.message().contains("tasks[0].modulePaths[0]"));assertFalse(canonical(failed).contains("private-value"));assertNotNull(failed.previousFailure);
            store.start(request);store.claim(id,"resume");new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"resume");
            assertEquals("READY",store.get(id).state,()->canonical(store.get(id).failure));assertEquals(taskId,store.get(id).taskId);assertEquals(4,calls.get());verifyNoInteractions(catalog);
        }
    }
    @Test void rootModuleIdentityMustBeCopiedExactlyAndNestedDirectoriesAreNotModules() {
        var root=new RepositoryCatalogClient.Inventory(request.repositoryKey(),"root",request.branch(),request.commitId(),null,List.of(new RepositoryCatalogClient.Module(1,"root","",request.branch(),List.of(request.branch()))),List.of("src/Main.java"),List.of());
        assertDoesNotThrow(()->validatePlan(new Plan("root",List.of(new Task("root","title",List.of(""),"objective",List.of()))),root));
        for(String invalid:List.of(".","src","root"))assertThrows(ReportValidationException.class,()->validatePlan(new Plan("bad",List.of(new Task("root","title",List.of(invalid),"objective",List.of()))),root));
    }

    @Test void synthesisFormatRepairDoesNotConsumeContractRepairAndAliasesRestoreEvidence() {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);var calls=new AtomicInteger();String id=id(request);
        try(var app=fixture.open("synthesis_separate_repairs_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"worker");
            store.update(id,"worker",r->{r.inventory=inventory;r.plan=plan();r.tasks=new ArrayList<>(plan().tasks().stream().map(TaskRun::new).toList());r.tasks.forEach(t->{String ref=t.task.id()+"-long-source-id";t.memory=memory(t.task.id(),ref);t.state="COMPLETED";t.sources.add(new RepositoryCatalogClient.SourceEvidence(ref,t.task.id().equals("identity")?2:1,request.commitId(),t.task.id()+"/Example.java",1,2,"CODE","class Example {}",false));});});
            String before=canonical(store.get(id).tasks);
            when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{Map<String,Object> c=inv.getArgument(1);
                assertEquals("SYNTHESIZE",c.get("stage"));assertEquals(List.of("S1","S2"),c.get("allowedEvidenceIds"));assertFalse(canonical(c.get("memories")).contains("long-source-id"));assertNotNull(c.get("requiredFields"));
                int n=calls.incrementAndGet();if(n==1)return new FlowModelClient.Response("{\"report\":","fixture");
                if(n==2){assertTrue(c.get("previousFailure").toString().contains("JSON_SYNTAX"));return response(new Synthesis(List.of("identity","billing"),new Report("完整报告",List.of(new ModuleRole("identity","身份",null)),List.of(),List.of())));}
                assertTrue(c.get("previousFailure").toString().contains("report.modules[0].evidenceIds"));
                return response(new Synthesis(List.of("identity","billing"),new Report("全部模块",List.of(new ModuleRole("identity","身份",List.of("S1")),new ModuleRole("billing","账单",List.of("S2"))),List.of(),List.of())));
            });
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"worker");var run=store.get(id);
            assertEquals("READY",run.state,()->canonical(run.failure));assertEquals(3,calls.get());assertEquals(before,canonical(run.tasks));
            assertEquals(List.of("identity-long-source-id"),run.report.modules().getFirst().evidenceIds());assertEquals(List.of("billing-long-source-id"),run.report.modules().get(1).evidenceIds());verifyNoInteractions(catalog);
        }
    }

    @Test void synthesisRetainsExactLastLimitFailureAndRetriesOnlyAllMemories() {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);var calls=new AtomicInteger();
        String id=id(request),db="synthesis_limits_"+UUID.randomUUID();String memories;
        when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{Map<String,Object> c=inv.getArgument(1);
            assertEquals("SYNTHESIZE",c.get("stage"));assertEquals(2,((List<?>)c.get("memories")).size());assertFalse(c.containsKey("sources"));
            assertEquals(30,((Map<?,?>)c.get("reportLimits")).get("questions"));assertTrue(c.get("consolidationInstructions").toString().contains("保留所有未解决事项"));
            int n=calls.incrementAndGet();if(n==2){assertTrue(c.get("previousFailure").toString().contains("report.questions 共 34 项"));assertNotNull(c.get("candidateToCorrect"));}
            if(n==3)assertTrue(c.get("previousFailure").toString().contains("report.questions 共 31 项"));
            return response(new Synthesis(List.of("identity","billing"),new Report("全部模块",List.of(new ModuleRole("identity","身份职责未知",List.of()),new ModuleRole("billing","账单职责未知",List.of())),List.of(),Collections.nCopies(n==1?34:n==2?31:2,"需要确认部署约束"))));
        });
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"first");
            store.update(id,"first",r->{r.inventory=inventory;r.plan=plan();r.tasks=new ArrayList<>(plan().tasks().stream().map(TaskRun::new).toList());r.tasks.forEach(t->{t.memory=new Report("未确认的职责",t.task.modulePaths().stream().map(m->new ModuleRole(m,"未知",List.<String>of())).toList(),List.of(),List.of("待查证"));t.state="COMPLETED";});});
            memories=canonical(store.get(id).tasks);
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"first");
            var run=store.get(id);assertEquals("FAILED",run.state);assertNull(run.report);assertEquals(2,run.synthesisAttempts);
            assertTrue(run.failure.message().contains("report.questions 共 31 项，最多允许 30 项"));assertEquals(run.failure.message(),run.previousFailure);assertEquals(memories,canonical(run.tasks));
        }
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"resume");
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"resume");
            var run=store.get(id);assertEquals("READY",run.state);assertEquals(3,run.synthesisAttempts);assertEquals(memories,canonical(run.tasks));assertEquals(List.of("identity","billing"),run.summarizedTaskIds);
        }
        verifyNoInteractions(catalog);verify(llm,times(3)).repositoryStage(any(),anyMap());
    }

    @Test void reportDiagnosticsExposeSchemaIndicesButNeverRejectedModelTitles() {
        var packet=packet(inventory,List.of());
        var invalid=new Report("summary",List.of(new ModuleRole("billing","role",List.of())),List.of(new Rule("secret-title-do-not-return","BUSINESS","description",List.of("billing"),Collections.nCopies(13,"value"),List.of(),List.of())),List.of());
        var error=assertThrows(ReportValidationException.class,()->validateReport(invalid,packet));
        var failure=FlowFailure.validation(error);
        assertTrue(failure.getMessage().contains("report.rules[0].clientPreparation 共 13 项，最多允许 12 项"));assertFalse(failure.getMessage().contains("secret-title"));
        assertNull(FlowFailure.safeValidationReason(new IllegalArgumentException("report.questions injected-secret")));
    }

    @Test void fileSelectionDiagnosticsSeparateCountDuplicateMissingAndVersionErrors() {
        var issues=toolIssues(new Decision("READ",Arrays.asList("identity/Identity.java","identity/Identity.java",null,"../private/secret","README.md","README.md","README.md"),null,null,null,null),inventory);
        assertTrue(issues.stream().anyMatch(s->s.contains("实际为 7 个")));
        assertTrue(issues.stream().anyMatch(s->s.contains("files[1]") && s.contains("重复")));
        assertTrue(issues.stream().anyMatch(s->s.contains("files[2]") && s.contains("不能为空")));
        assertTrue(issues.stream().anyMatch(s->s.contains("files[3]") && s.contains("当前仓库提交")));
        assertFalse(canonical(issues).contains("private/secret"));
        assertFalse(toolIssues(new Decision("READ",null,null,null,null,null),inventory).isEmpty());
        assertFalse(toolIssues(new Decision("READ",List.of(),null,null,null,null),inventory).isEmpty());
        assertFalse(toolIssues(new Decision("OTHER",null,null,null,null,null),inventory).isEmpty());
        assertEquals(3,toolIssues(new Decision("LIST",null,"../private","x".repeat(201),-1,null),inventory).size());
        // Cross-module evidence is allowed, but only at the exact pinned repository revision.
        assertTrue(toolIssues(new Decision("READ",List.of("billing/Billing.java","identity/Identity.java"),null,null,null,null),inventory).isEmpty());
    }

    @Test void rejectedReadAndListAreCorrectedBeforeAnyFileAccess() { exerciseToolRecovery(false); }
    @Test void repeatedInvalidReadStopsAfterTwoCorrectionsAndResumesWithoutLosingMemory() { exerciseToolRecovery(true); }
    @SuppressWarnings("unchecked") private void exerciseToolRecovery(boolean exhaust) {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);
        var calls=new AtomicInteger();String id=id(request),db="tool_repair_"+UUID.randomUUID();
        when(catalog.readFiles(eq(request),eq(List.of("billing/Billing.java")))).thenReturn(List.of(new RepositoryCatalogClient.SourceEvidence("billing-ref",1,request.commitId(),"billing/Billing.java",1,2,"CODE","class Billing {}",false)));
        when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{Map<String,Object> c=inv.getArgument(1);
            if(c.get("stage").equals("ANALYZE")) {
                assertEquals("billing",((Task)c.get("task")).id());assertEquals(1,((List<?>)c.get("upstreamMemories")).size());
                assertEquals(6,((Map<?,?>)c.get("toolConstraints")).get("maxFilesPerRead"));
                int n=calls.incrementAndGet();
                if(n>1 && n<=3){assertTrue(c.get("toolFeedback").toString().contains("工具请求未执行"));assertTrue(((List<Map<String,Object>>)c.get("toolHistory")).stream().allMatch(h->Boolean.TRUE.equals(h.get("rejected"))));}
                if(n==1 || (exhaust && n<=3))return response(new Decision("READ",List.of("billing/Guessed.java"),null,null,null,null));
                if(!exhaust && n==2)return response(new Decision("LIST",null,"../outside","",-1,null));
                var sources=(List<RepositoryCatalogClient.SourceEvidence>)c.get("sources");
                if(sources.isEmpty())return response(new Decision("READ",List.of("billing/Billing.java"),null,null,null,null));
                return response(new Decision("DONE",null,null,null,null,memory("billing",sources.getFirst().id())));
            }
            assertEquals("SYNTHESIZE",c.get("stage"));var memories=(List<Map<String,Object>>)c.get("memories");assertEquals(2,memories.size());
            return response(new Synthesis(List.of("identity","billing"),new Report("全部模块",memories.stream().flatMap(m->((Report)m.get("memory")).modules().stream()).toList(),List.of(),List.of())));
        });
        String upstream;
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"worker");
            store.update(id,"worker",r->{r.inventory=inventory;r.plan=plan();r.tasks=new ArrayList<>(plan().tasks().stream().map(TaskRun::new).toList());var t=r.tasks.getFirst();t.state="COMPLETED";t.memory=memory("identity","identity-ref");t.attempts=2;t.sources.add(new RepositoryCatalogClient.SourceEvidence("identity-ref",2,request.commitId(),"identity/Identity.java",1,2,"CODE","class Identity {}",false));});
            upstream=canonical(store.get(id).tasks.getFirst());
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"worker");
            var run=store.get(id);assertEquals(upstream,canonical(run.tasks.getFirst()));
            assertEquals(exhaust?"FAILED":"READY",run.state,()->canonical(run.failure));
            assertFalse(canonical(run).contains("Guessed.java"));
            if(exhaust) {
                assertEquals(3,calls.get());assertEquals(3,run.tasks.get(1).toolHistory.size());verifyNoInteractions(catalog);
                assertTrue(run.failure.message().contains("READ.files[0]"));assertTrue(run.tasks.get(1).toolFeedback.contains("次数已用完"));
            } else {assertEquals(2,run.tasks.get(1).toolHistory.stream().filter(h->Boolean.TRUE.equals(h.get("rejected"))).count());assertNull(run.tasks.get(1).error);}
        }
        if(exhaust)try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"resumed");
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"resumed");
            assertEquals("READY",store.get(id).state,()->canonical(store.get(id).failure));assertEquals(upstream,canonical(store.get(id).tasks.getFirst()));
        }
        verify(catalog,times(1)).readFiles(eq(request),eq(List.of("billing/Billing.java")));verifyNoMoreInteractions(catalog);
    }

    @Test void planMustCoverEveryModuleAndDependenciesCannotPointForward() {
        assertDoesNotThrow(()->validatePlan(plan(),inventory));
        assertThrows(IllegalArgumentException.class,()->validatePlan(new Plan("遗漏",List.of(plan().tasks().getFirst())),inventory));
        assertThrows(IllegalArgumentException.class,()->validatePlan(new Plan("逆序",List.of(plan().tasks().get(1),plan().tasks().getFirst())),inventory));
        assertThrows(IllegalArgumentException.class,()->validatePlan(new Plan("重复",List.of(plan().tasks().getFirst(),plan().tasks().getFirst())),inventory));
        var overview=overview(inventory);assertFalse(overview.containsKey("sources"));assertFalse(overview.containsKey("files"));assertEquals(2,((List<?>)overview.get("modules")).size());
    }

    @Test void failedMiddleTaskResumesAtCheckpointAndFinalSynthesisReceivesEveryMemory() {
        exercise(true,false,false);
    }
    @Test void failedSynthesisRetriesOnlySynthesisAndCannotClaimLastModuleIsWholeRepository() {
        exercise(false,true,false);
    }
    @Test void cachedReadsAndOversizedMemoryAreCorrectedWithoutRereadingSources() {
        exercise(false,true,true);
    }
    @SuppressWarnings("unchecked") private void exercise(boolean failModule,boolean failSynthesis,boolean repairMemory) {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);
        when(catalog.inventory(anyString(),anyString(),anyString(),anyString())).thenReturn(inventory);
        when(catalog.readFiles(any(),anyList())).thenAnswer(inv->{List<String> files=inv.getArgument(1);return files.stream().map(file->new RepositoryCatalogClient.SourceEvidence("ref-"+file.split("/")[0],file.startsWith("billing")?1:2,request.commitId(),file,1,4,"REPOSITORY_CODE","class Example {}",false)).toList();});
        var repeatRead=new AtomicBoolean(repairMemory);var oversized=new AtomicBoolean(repairMemory);
        var moduleFailure=new AtomicBoolean(failModule);var synthesisFailure=new AtomicInteger(failSynthesis?2:0);var calls=new ArrayList<String>();
        when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{
            Map<String,Object> c=inv.getArgument(1);assertEquals(request.goal(),c.get("goal"));String stage=(String)c.get("stage");calls.add(stage);
            if(stage.equals("PLAN"))return response(plan());
            if(stage.equals("ANALYZE")) {
                var task=(Task)c.get("task");
                if(task.id().equals("billing") && moduleFailure.getAndSet(false))throw new FlowFailure(FlowFailure.Code.MODEL_TIMEOUT);
                var sources=(List<RepositoryCatalogClient.SourceEvidence>)c.get("sources");
                if(sources.isEmpty())return response(new Decision("READ",List.of(task.id()+"/"+(task.id().equals("billing")?"Billing":"Identity")+".java"),null,null,null,null));
                if(task.id().equals("identity") && repeatRead.getAndSet(false))return response(new Decision("READ",List.of("identity/Identity.java"),null,null,null,null));
                if(task.id().equals("identity") && oversized.getAndSet(false)) {
                    assertTrue(c.get("toolFeedback").toString().contains("已经读取"));
                    var base=memory(task.id(),sources.getFirst().id());
                    return response(new Decision("DONE",null,null,null,null,new Report(base.summary(),base.modules(),base.rules(),Collections.nCopies(20,"需要确认部署背景。".repeat(50)))));
                }
                if(task.id().equals("identity") && repairMemory)assertTrue(c.get("toolFeedback").toString().contains("压缩"));
                if(task.id().equals("billing"))assertEquals(1,((List<?>)c.get("upstreamMemories")).size());
                return response(new Decision("DONE",null,null,null,null,memory(task.id(),sources.getFirst().id())));
            }
            var memories=(List<Map<String,Object>>)c.get("memories");assertEquals(2,memories.size());assertFalse(c.containsKey("sources"));
            assertEquals(Set.of("identity","billing"),new HashSet<>((List<String>)c.get("expectedTaskIds")));
            var roles=memories.stream().flatMap(m->((Report)m.get("memory")).modules().stream()).toList();
            var rules=memories.stream().flatMap(m->((Report)m.get("memory")).rules().stream()).toList();
            return response(new Synthesis(synthesisFailure.getAndDecrement()>0?List.of("billing"):List.of("identity","billing"),new Report("完整仓库：身份和账单共同构成业务",roles,rules,List.of("部署配置仍待确认"))));
        });
        String db="orchestration_"+UUID.randomUUID();String id=id(request);String firstMemory;
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"one");
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"one");
            var failed=store.get(id);assertEquals("FAILED",failed.state);assertNull(failed.report);
            firstMemory=canonical(failed.tasks.getFirst().memory);assertNotEquals("null",firstMemory);
            assertEquals(failModule?"REPOSITORY_MODULE":"REPOSITORY_SYNTHESIS",failed.failure.stage());
            assertFalse(store.update(id,"one",r->r.state="READY"));
        }
        try(var app=fixture.open(db)) {
            var store=app.getBean(RepositoryAnalysisStore.class);
            assertThrows(IllegalArgumentException.class,()->store.start(new Request(request.repositoryKey(),request.branch(),request.commitId(),request.environment(),request.model(),false,"different goal")));
            store.start(new Request(request.repositoryKey(),request.branch(),request.commitId(),request.environment(),request.model()));store.claim(id,"two");
            assertEquals(request.goal(),store.get(id).request.goal());
            assertEquals(firstMemory,canonical(store.get(id).tasks.getFirst().memory));
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"two");
            var complete=store.get(id);assertEquals("READY",complete.state,()->canonical(complete.failure));
            assertEquals(firstMemory,canonical(complete.tasks.getFirst().memory));assertEquals(repairMemory?4:2,complete.tasks.getFirst().attempts);
            var projection=progress(complete);assertEquals("COMPLETED",projection.tasks().getFirst().activity());
            assertEquals(List.of("identity/Identity.java"),projection.tasks().getFirst().sourceFiles());
            assertEquals("READ",projection.tasks().getFirst().toolHistory().getFirst().get("action"));assertNotNull(projection.tasks().getFirst().toolHistory().getFirst().get("at"));
            assertEquals(failSynthesis?3:1,projection.synthesisAttempts());
            assertEquals(List.of("identity","billing"),complete.summarizedTaskIds);assertEquals(2,complete.report.modules().size());
            assertEquals(1,Collections.frequency(calls,"PLAN"));assertEquals(failSynthesis?3:1,Collections.frequency(calls,"SYNTHESIZE"));
            verify(catalog,times(1)).inventory(anyString(),anyString(),anyString(),anyString());verify(catalog,times(2)).readFiles(any(),anyList());
            store.start(new Request(request.repositoryKey(),request.branch(),request.commitId(),request.environment(),request.model(),true));assertNull(store.get(id).plan);
        }
    }

    @Test void exhaustedCheckpointFinishesWithExplicitUnknownsWithoutRestartingPlanOrTools() {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);
        var summaryAttempts=new AtomicInteger();
        when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{
            Map<String,Object> c=inv.getArgument(1);
            if(c.get("stage").equals("ANALYZE")) {
                if(((Number)c.get("remainingRounds")).intValue()>0)return response(new Decision("LIST",null,"","",0,null));
                assertEquals(List.of("DONE"),c.get("allowedActions"));assertEquals(0,c.get("remainingRounds"));assertNotNull(c.get("toolHistory"));
                assertTrue(c.get("toolFeedback").toString().contains("本次查询与上次相同"));
                var task=(Task)c.get("task");
                return response(new Decision("DONE",null,null,null,null,new Report("取证预算耗尽，职责未确认",task.modulePaths().stream().map(p->new ModuleRole(p,"未知：缺少可用源码证据",List.<String>of())).toList(),List.of(),List.of("需补充模块源码分析"))));
            }
            boolean first=summaryAttempts.getAndIncrement()==0;
            if(!first){assertTrue(c.get("previousFailure").toString().contains("report.questions 共 31 项"));assertNotNull(c.get("candidateToCorrect"));assertEquals(2,((List<?>)c.get("memories")).size());}
            return response(new Synthesis(List.of("identity","billing"),new Report("模块尚无源码证据，不能推断运行约束",inventory.modules().stream().map(m->new ModuleRole(m.modulePath(),"未知：取证预算耗尽",List.<String>of())).toList(),List.of(),first?Collections.nCopies(31,"需进一步分析"):List.of("需进一步分析"))));
        });
        try(var app=fixture.open("budget_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id(request),"worker");
            store.update(id(request),"worker",r->{r.inventory=inventory;r.plan=plan();r.tasks=new ArrayList<>(plan().tasks().stream().map(TaskRun::new).toList());r.tasks.forEach(t->{t.rounds=10;t.toolHistory=null;});});
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id(request),"worker");
            var result=store.get(id(request));assertEquals("READY",result.state,()->canonical(result.failure));assertEquals(2,result.summarizedTaskIds.size());
            assertTrue(result.packet.warnings().stream().anyMatch(w->w.contains("尚无本模块可用文件证据")));verifyNoInteractions(catalog);verify(llm,times(8)).repositoryStage(any(),anyMap());
        }
    }

    @Test void progressShowsActionsWithoutSourceBodiesAndHandlesOldInterruptedSnapshots() {
        var run=new Run(request);run.plan=plan();run.state="FAILED";run.synthesisAttempts=3;
        var task=new TaskRun(plan().tasks().getFirst());task.state="ANALYZING";task.activity="READING";task.pendingFiles=List.of("identity/Identity.java");
        task.sources.add(new RepositoryCatalogClient.SourceEvidence("ref",2,request.commitId(),"identity/Identity.java",1,4,"REPOSITORY_CODE","source body must not be copied into progress",false));
        task.toolHistory=null;run.tasks=List.of(task);run.currentTaskId=task.task.id();
        var view=progress(run);assertEquals(3,view.synthesisAttempts());assertEquals("FAILED",view.tasks().getFirst().state());
        assertEquals(List.of("identity/Identity.java"),view.tasks().getFirst().sourceFiles());assertTrue(view.tasks().getFirst().toolHistory().isEmpty());
        assertFalse(canonical(view).contains("source body must not be copied"));
        run.state="CANCELLED";assertEquals("CANCELLED",progress(run).tasks().getFirst().state());
    }

    @Test void repositoryFileSearchCannotEscapeAndReturnsExplicitPagination() {
        var files=new ArrayList<String>();for(int i=0;i<151;i++)files.add("billing/F"+i+".java");
        var index=new RepositoryCatalogClient.Inventory(inventory.repositoryKey(),inventory.name(),inventory.branch(),inventory.commitId(),inventory.environment(),inventory.modules(),files,List.of());
        var page=filePage(index,plan().tasks().get(1),"billing/","",0,false);assertEquals(true,page.get("hasMore"));assertEquals(150,((List<?>)page.get("files")).size());
        assertThrows(IllegalArgumentException.class,()->filePage(index,plan().tasks().getFirst(),"../other","",0,false));
    }
    @Test void malformedJsonIsRepairedWithoutRereadingAndSynthesisReusesAllMemories() {
        var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);
        var calls=new AtomicInteger();var summaries=new AtomicInteger();
        var plan=new Plan("归纳全部模块",List.of(new Task("all","基础组件",List.of("billing","identity"),"分析交互",List.of())));
        var fixture=new JpaChainWorkspaceStoreTest();String id=id(request);
        try(var app=fixture.open("format_recovery_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);store.start(request);store.claim(id,"worker");
            store.update(id,"worker",r->{r.inventory=inventory;r.plan=plan;r.tasks=new ArrayList<>(List.of(new TaskRun(plan.tasks().getFirst())));var task=r.tasks.getFirst();task.rounds=12;
                task.sources.add(new RepositoryCatalogClient.SourceEvidence("billing-source",1,request.commitId(),"billing/Billing.java",1,2,"CODE","class Billing {}",false));
                task.sources.add(new RepositoryCatalogClient.SourceEvidence("identity-source",2,request.commitId(),"identity/Identity.java",1,2,"CODE","class Identity {}",false));
                task.sources.add(new RepositoryCatalogClient.SourceEvidence("unsupported",2,request.commitId(),"README.md",1,2,"UNSUPPORTED","",false));});
            when(llm.repositoryStage(any(),anyMap())).thenAnswer(inv->{Map<String,Object> c=inv.getArgument(1);
                if(c.get("stage").equals("ANALYZE")) {
                    assertEquals(List.of("DONE"),c.get("allowedActions"));assertEquals(List.of("S1","S2"),c.get("allowedEvidenceIds"));
                    int n=calls.incrementAndGet();
                    if(n==1)return new FlowModelClient.Response("{\"action\":","fixture");
                    assertTrue(c.get("toolFeedback").toString().contains(n==2?"JSON_SYNTAX":"Decision.memory.modules"));
                    if(n==2)return new FlowModelClient.Response("{\"action\":\"DONE\",\"memory\":{\"modules\":\"bad-shape\"}}","fixture");
                    return response(new Decision("DONE",null,null,null,null,new Report("全部模块",List.of(new ModuleRole("billing","账单",List.of("S1")),new ModuleRole("identity","身份",List.of("S2"))),List.of(),List.of())));
                }
                assertEquals(1,((List<?>)c.get("memories")).size());assertFalse(c.containsKey("sources"));
                if(summaries.incrementAndGet()==1)return new FlowModelClient.Response("truncated JSON","fixture");
                assertTrue(c.get("previousFailure").toString().contains("JSON_SYNTAX"));
                return response(new Synthesis(List.of("all"),(Report)((List<Map<String,Object>>)c.get("memories")).getFirst().get("memory")));
            });
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"worker");
            assertEquals("READY",store.get(id).state);assertEquals(3,calls.get());assertEquals(2,summaries.get());
            assertEquals(3,store.get(id).tasks.getFirst().sources.size());verifyNoInteractions(catalog);
        }
    }
    @Test void repeatedMalformedJsonStopsAfterTwoCorrectionsAndKeepsCheckpoint() {
        var fixture=new JpaChainWorkspaceStoreTest();var catalog=mock(RepositoryCatalogClient.class);var llm=mock(FlowModelClient.class);
        when(catalog.inventory(any(),any(),any(),any())).thenReturn(inventory);
        when(llm.repositoryStage(any(),anyMap())).thenReturn(new FlowModelClient.Response("invalid","fixture"));
        try(var app=fixture.open("format_bound_"+UUID.randomUUID())) {
            var store=app.getBean(RepositoryAnalysisStore.class);String id=id(request);store.start(request);store.claim(id,"worker");
            new RepositoryAnalysisWorkflow(store,catalog,llm).execute(id,"worker");
            assertEquals("FAILED",store.get(id).state);assertNotNull(store.get(id).inventory);
            assertTrue(store.get(id).failure.message().contains("JSON_SYNTAX"));verify(llm,times(3)).repositoryStage(any(),anyMap());
        }
    }

}
