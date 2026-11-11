package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;

class StepSuggestionServiceTest {
    @Test void modelReceivesToolsAndCanAddAnExecutablePreparationPlanWithoutChangingOtherNodes() {
        var plan=new io.yuan.octopus.preparation.PreparationPlan(List.of(
            new io.yuan.octopus.preparation.PreparationPlan.Call("clock","time.now","1",Map.of()),
            new io.yuan.octopus.preparation.PreparationPlan.Call("text","string.value","1",Map.of("value",new io.yuan.octopus.preparation.PreparationPlan.Value("result","clock.value")))
        ),null,Map.of("timestamp",new io.yuan.octopus.preparation.PreparationPlan.Value("result","text.value")));
        var fill=new FlowSuggestionService.NodeFill("create",Map.of("userId",new Binding("context","test.user")),List.of(),plan);
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response(canonical(new StepSuggestionService.Patch(fill,List.of(),
            new StepMemoryService.MemoryDraft("动态请求头",List.of("按每次请求生成时间戳"),List.of(),List.of("source-1")),List.of(),List.of())),"fixture"));
        var result=service.suggest(request("create"));
        assertEquals(canonical(plan),canonical(result.flow().nodes().getFirst().preparation()));
        assertEquals(fixture.flow.nodes().get(1),result.flow().nodes().get(1));
        assertTrue(result.memory().memory().inputBindings().stream().anyMatch(s -> s.contains("time.now@1")));
        var payload=ArgumentCaptor.forClass(Map.class); verify(fixture.llm).step(any(),payload.capture(),any());
        assertEquals(8,((List<?>)payload.getValue().get("preparationTools")).size());
        var format=(Map<?,?>)payload.getValue().get("preparationPlanFormat");
        var sample=(io.yuan.octopus.preparation.PreparationPlan)format.get("example");
        io.yuan.octopus.preparation.PreparationTools.validate(sample);
        assertEquals("result",sample.tools().get(1).inputs().get("value").source());
        assertTrue(canonical(format).contains("requiredFields"));
        assertEquals(hash(result.flow()),hash(read(canonical(result.flow()),Flow.class)));
    }
    FlowSuggestionServiceTest fixture; StepMemoryService memories; StepSuggestionService service;
    RepositoryCatalogClient.SourceContext source; ProjectContextService.Definition definition;
    @BeforeEach void setup() {
        fixture=new FlowSuggestionServiceTest(); fixture.setup(); memories=mock(StepMemoryService.class);
        definition=new ProjectContextService.Definition("项目背景","由人定义业务规则","https://private-environment.test",List.of());
        when(fixture.context.resolve(eq("repo:1"),anyString(),eq("test"))).thenAnswer(i->new ProjectContextService.Resolved(
            new ProjectContextService.View("repo:1",i.getArgument(1),"test",1L,definition,Map.of(),null),definition,Map.of(),false,true));
        var impl=fixture.operation.implementations().getFirst();
        var contract=new RepositoryCatalogClient.Contract(52,"master",impl.commitId(),impl.signature(),"POST","/orders",fixture.operation.inputs(),fixture.operation.requestSchema(),fixture.operation.responseSchema(),List.of());
        source=new RepositoryCatalogClient.SourceContext(contract,List.of(new RepositoryCatalogClient.SourceEvidence("source-1",52,impl.commitId(),"Orders.java",5,20,"ENTRY_METHOD","Result create(Req req) { return service.create(req); }",false)),List.of("网关尚未覆盖"));
        when(fixture.catalog.sourceContext(impl,"POST","/orders")).thenReturn(source);
        when(memories.views(any(),anyString(),anyMap())).thenReturn(List.of());
        when(memories.propose(any(),anyString(),anyString(),anyMap(),anyString(),any())).thenAnswer(i->new StepMemoryService.View("pending",i.getArgument(1),"PENDING","fixture","now",i.getArgument(5)));
        service=new StepSuggestionService(mock(FlowContractService.class),fixture.catalog,fixture.context,fixture.store,fixture.llm,memories);
        respond("create",new Binding("context","test.user"),List.of(new Output("order.id","$.body.id","string",false)),List.of());
    }
    StepSuggestionService.Request request(String node){return new StepSuggestionService.Request(fixture.flow,node,"test","创建订单后查询","fixture",Map.of("test.user","fixture-user"),List.of("userId"));}
    void respond(String node,Binding binding,List<Output> outputs,List<FlowSuggestionService.AssertionFill> assertions){
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response(canonical(new StepSuggestionService.Patch(fixture.fill(node,binding,outputs),assertions,
            new StepMemoryService.MemoryDraft("fixture-user 的步骤结论",List.of("使用已有上下文"),List.of("需要确认网关"),List.of("source-1")),List.of(),List.of())),"fixture"));
    }
    StepMemoryService.View memory(String node,String state){return new StepMemoryService.View(node,node,state,"fixture","now",new StepMemoryService.Memory(node+" memory",List.of(),List.of(),List.of(),List.of(),List.of(),fixture.operation.ref()));}
    @Test void currentNodeGetsVersionedSourceAndProjectModuleBackgroundWithoutTouchingOtherNodes() {
        var result=service.suggest(request("create"));
        assertEquals("create",result.nodeId()); assertEquals(fixture.flow.nodes().get(1),result.flow().nodes().get(1));
        assertEquals(fixture.flow.testPoints(),result.flow().testPoints()); assertEquals("PENDING",result.memory().state());
        assertFalse(canonical(result.memory()).contains("fixture-user")); assertTrue(result.memory().memory().summary().contains("测试值已省略"));
        var context=ArgumentCaptor.forClass(Map.class); verify(fixture.llm).step(any(),context.capture(),any());
        var text=canonical(context.getValue()); assertTrue(text.contains("ENTRY_METHOD")); assertTrue(text.contains("projectBackground")); assertTrue(text.contains("moduleBackground"));
        assertTrue(text.contains("此版本尚未进行仓库背景分析")); assertTrue(result.memory().memory().questions().contains(StepSuggestionService.COVERAGE_LIMIT));
        assertTrue(text.contains("fixture-user")); assertFalse(text.contains("private-environment")); assertEquals(fixture.operation,context.getValue().get("operation"));
        verify(memories,never()).confirm(any(),any(),any(),any()); verify(fixture.store,never()).saveDraft(any()); verify(fixture.catalog,never()).endpoints(any(),any());
    }
    @Test void onlyConfirmedAncestorsReachModelAndStaleOrFutureMemoriesAreExcluded() {
        when(memories.views(any(),anyString(),anyMap())).thenReturn(List.of(memory("create","CONFIRMED"),memory("query","CONFIRMED"),memory("unrelated","CONFIRMED")));
        respond("query",new Binding("context","test.user"),List.of(),List.of()); service.suggest(request("query"));
        var context=ArgumentCaptor.forClass(Map.class); verify(fixture.llm).step(any(),context.capture(),any());
        assertEquals(List.of(memory("create","CONFIRMED")),context.getValue().get("upstreamMemories"));
        when(memories.views(any(),anyString(),anyMap())).thenReturn(List.of(memory("create","STALE")));
        var stale=service.suggest(request("query")); assertTrue(stale.warnings().stream().anyMatch(w->w.contains("过期")));
        verify(fixture.llm,times(2)).step(any(),context.capture(),any()); assertEquals(List.of(),context.getValue().get("upstreamMemories"));
    }
    @Test void rejectsOtherNodeChangesOtherNodeAssertionsAndInventedEvidence() {
        respond("query",null,List.of(),List.of()); assertThrows(IllegalStateException.class,()->service.suggest(request("create")));
        respond("create",null,List.of(),List.of(new FlowSuggestionService.AssertionFill("normal","query",new Assertion("new","JSON_EXISTS","$.body.id",true))));
        assertThrows(IllegalStateException.class,()->service.suggest(request("create")));
        respond("create",null,List.of(),List.of());
        var content=fixture.llm.step("fixture",Map.of(),null).content().replace("source-1","invented-source");
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response(content,"fixture"));
        assertThrows(IllegalStateException.class,()->service.suggest(request("create"))); verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
    }
    @Test void rejectsMismatchedSourceCommitBeforeModelCall() {
        var c=source.contract(); var wrong=new RepositoryCatalogClient.Contract(c.projectId(),c.branch(),"b".repeat(40),c.signature(),c.httpMethod(),c.path(),c.inputs(),c.requestSchema(),c.responseSchema(),c.warnings());
        when(fixture.catalog.sourceContext(any(),any(),any())).thenReturn(new RepositoryCatalogClient.SourceContext(wrong,source.sources(),List.of()));
        assertThrows(IllegalArgumentException.class,()->service.suggest(request("create"))); verify(fixture.llm,never()).step(any(),any(),any());
    }
    @Test void chainOnlyUsesItsWorkingAncestorsAndKeepsThemExplicitlyUnreviewed() {
        var working=memory("create","WORKING");
        when(memories.working(eq(List.of("candidate-1")),any(),eq("test"),anyMap())).thenReturn(List.of(working,memory("unrelated","WORKING")));
        when(memories.views(any(),anyString(),anyMap())).thenReturn(List.of(memory("create","CONFIRMED")));
        respond("query",new Binding("context","test.user"),List.of(),List.of());
        service.suggest(request("query"),List.of("candidate-1"));
        var context=ArgumentCaptor.forClass(Map.class);verify(fixture.llm).step(any(),context.capture(),any());
        assertEquals(List.of(working),context.getValue().get("upstreamMemories"));
        verify(memories,never()).confirm(any(),any(),any(),any());
    }
    @Test void repositoryRulesAndGatewayEvidenceReachNodeAndInvalidateMemoryWhenChanged() {
        var analysis=mock(RepositoryAnalysisService.class);
        service=new StepSuggestionService(mock(FlowContractService.class),fixture.catalog,fixture.context,fixture.store,fixture.llm,memories,analysis);
        var impl=fixture.operation.implementations().getFirst();
        var report=new RepositoryAnalysisService.Report("Token 豁免不等于签名豁免",List.of(),List.of(new RepositoryAnalysisService.Rule("签名", "SIGNING", "必须传 sign",List.of("orders"),List.of("使用运行时密钥签名"),List.of("gateway-source"),List.of())),List.of());
        var gateway=new RepositoryCatalogClient.SourceEvidence("gateway-source",53,impl.commitId(),"gateway/AuthFilter.java",1,40,"REPOSITORY_CODE","request.getHeader(\"sign\")",false);
        var request=new RepositoryAnalysisService.Request("repo:1",impl.branch(),impl.commitId(),"test","fixture");
        when(analysis.reference("repo:1",impl.branch(),impl.commitId(),"test")).thenReturn(new RepositoryAnalysisService.View("repo-background","READY",request,"fixture","now","now",report,List.of(gateway),List.of(),null));
        var hashes=ArgumentCaptor.forClass(Map.class);service.suggest(request("create"));verify(memories,atLeastOnce()).views(any(),anyString(),hashes.capture());String first=canonical(hashes.getValue());
        var model=ArgumentCaptor.forClass(Map.class);verify(fixture.llm).step(any(),model.capture(),any());
        assertTrue(canonical(model.getValue()).contains("Token 豁免不等于签名豁免"));assertTrue(canonical(model.getValue()).contains("gateway/AuthFilter.java"));
        var changed=new RepositoryAnalysisService.Report("配置规则已更新",report.modules(),report.rules(),report.questions());
        when(analysis.reference("repo:1",impl.branch(),impl.commitId(),"test")).thenReturn(new RepositoryAnalysisService.View("repo-background","READY",request,"fixture","now","later",changed,List.of(gateway),List.of(),null));
        service.suggest(request("create"));verify(memories,atLeastOnce()).views(any(),anyString(),hashes.capture());assertNotEquals(first,canonical(hashes.getValue()));
        when(analysis.reference("repo:1",impl.branch(),impl.commitId(),"test")).thenThrow(new FlowFailure(FlowFailure.Code.CONTEXT_UNAVAILABLE));
        assertThrows(FlowFailure.class,()->service.suggest(request("create")));verify(fixture.llm,times(2)).step(any(),any(),any());
    }

    @Test void bindingCatalogKeepsOnlyUpstreamOutputsAndCompatibleTypes() {
        var f=fixture.flow; var first=f.nodes().getFirst(); var second=f.nodes().get(1);
        fixture.flow=new Flow(f.flowId(),f.revision(),f.name(),Map.of("test.user",new Variable("string",false),"test.body",new Variable("object",false)),List.of(
            new FlowNode(first.id(),first.label(),first.operationRef(),first.inputs(),List.of(new Output("login.flToken","$.body.id","string",true)),first.states(),0),
            new FlowNode(second.id(),second.label(),second.operationRef(),second.inputs(),List.of(new Output("later.id","$.body.id","string",false)),second.states(),0)),f.dependencies(),f.stateModels(),f.testPoints());
        respond("query",new Binding("context","login.flToken"),List.of(),List.of());
        var result=service.suggest(request("query"));
        assertEquals("login.flToken",result.flow().nodes().get(1).inputs().get("userId").value());
        var payload=ArgumentCaptor.forClass(Map.class);verify(fixture.llm).step(any(),payload.capture(),any());
        var path=(Map<?,?>)payload.getValue().get("path");
        var options=(Map<?,?>)((Map<?,?>)path.get("inputBindingOptions")).get("userId");
        assertEquals(List.of("login.flToken","test.user"),options.get("contextKeys"));
        assertEquals("string",options.get("expectedType"));
        assertFalse(((Map<?,?>)path.get("availableContext")).containsKey("later.id"));
    }

    @Test void unknownContextGetsOneBoundedCorrectionWithoutPersistingOrEchoingModelValue() {
        respond("create",new Binding("context","private-unregistered-value"),List.of(),List.of());
        var error=assertThrows(FlowFailure.class,()->service.suggest(request("create")));
        var detail=error.detail("GENERATING","create");
        assertEquals("MODEL_PATCH_INVALID",detail.code());
        assertEquals("CONTEXT_UNDECLARED",detail.bindingIssue().code());
        assertEquals("userId",detail.bindingIssue().input());
        assertEquals(List.of("test.user"),detail.bindingIssue().availableContextKeys());
        assertFalse(canonical(detail).contains("private-unregistered-value"));
        var payload=ArgumentCaptor.forClass(Map.class);verify(fixture.llm,times(2)).step(any(),payload.capture(),any());
        assertFalse(payload.getAllValues().getFirst().containsKey("validationFeedback"));
        assertEquals(detail.bindingIssue(),payload.getValue().get("validationFeedback"));
        assertFalse(canonical(payload.getValue()).contains("private-unregistered-value"));
        verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
        // Older persisted failures still deserialize with no field diagnostics.
        var old=read("{\"code\":\"MODEL_PATCH_INVALID\",\"message\":\"旧错误\"}",FlowFailure.Detail.class);
        assertNull(old.bindingIssue());
    }

    @Test void typeMismatchReportsDeclaredSourceAndExpectedTypeWithoutCoercing() {
        var f=fixture.flow;
        fixture.flow=new Flow(f.flowId(),f.revision(),f.name(),Map.of("test.user",new Variable("string",false),"test.body",new Variable("object",false)),f.nodes(),f.dependencies(),f.stateModels(),f.testPoints());
        respond("create",new Binding("context","test.body"),List.of(),List.of());
        var issue=assertThrows(FlowFailure.class,()->service.suggest(request("create"))).detail("GENERATING","create").bindingIssue();
        assertEquals("CONTEXT_TYPE_MISMATCH",issue.code());assertEquals("string",issue.expectedType());
        assertEquals("object",issue.actualType());assertEquals("test.body",issue.contextKey());
        verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
    }

    @Test void correctedCandidateUsesOriginalCheckpointAndCreatesOnlyOnePendingMemory() {
        String before=canonical(fixture.flow);
        respond("create",new Binding("context","unknown-secret-looking-value"),List.of(),List.of());
        var bad=fixture.llm.step("fixture",Map.of(),null);
        respond("create",new Binding("context","test.user"),List.of(new Output("order.id","$.body.id","string",false)),List.of());
        var good=fixture.llm.step("fixture",Map.of(),null);
        clearInvocations(fixture.llm);when(fixture.llm.step(any(),any(),any())).thenReturn(bad,good);
        var result=service.suggest(request("create"));
        assertEquals(before,canonical(fixture.flow));assertEquals("PENDING",result.memory().state());
        assertEquals(1,result.flow().nodes().getFirst().outputs().size());
        assertEquals(fixture.flow.nodes().get(1),result.flow().nodes().get(1));
        assertTrue(result.warnings().stream().anyMatch(w->w.contains("重新生成一次")));
        assertFalse(canonical(result).contains("unknown-secret-looking-value"));
        verify(fixture.llm,times(2)).step(any(),any(),any());verify(memories).propose(any(),any(),any(),any(),any(),any());
    }

    @Test void toolResultsAreNotImplicitlyPromotedToWorkflowContext() {
        var plan=new io.yuan.octopus.preparation.PreparationPlan(List.of(new io.yuan.octopus.preparation.PreparationPlan.Call("req","uuid","1",Map.of())),null,Map.of());
        var fill=new FlowSuggestionService.NodeFill("create",Map.of("userId",new Binding("context","req.value")),List.of(),plan);
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response(canonical(new StepSuggestionService.Patch(fill,List.of(),
            new StepMemoryService.MemoryDraft("请求",List.of(),List.of(),List.of()),List.of(),List.of())),"fixture"));
        assertEquals("CONTEXT_UNDECLARED",assertThrows(FlowFailure.class,()->service.suggest(request("create"))).detail("GENERATING","create").bindingIssue().code());
        verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
    }

    @Test void malformedJsonIsCorrectedFromSameCheckpointWithoutPersistingBadText() {
        var good=fixture.llm.step("fixture",Map.of(),null); clearInvocations(fixture.llm);
        String original=canonical(fixture.flow);
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response("{\"private-secret\":", "fixture"),good);
        var result=service.suggest(request("create"));
        assertEquals(original,canonical(fixture.flow)); assertEquals("PENDING",result.memory().state());
        var payload=ArgumentCaptor.forClass(Map.class);verify(fixture.llm,times(2)).step(any(),payload.capture(),any());
        assertFalse(payload.getAllValues().getFirst().containsKey("formatFeedback"));
        assertTrue(canonical(payload.getValue().get("formatFeedback")).contains("JSON_SYNTAX"));
        assertFalse(canonical(payload.getValue()).contains("private-secret"));
        assertEquals(payload.getAllValues().getFirst().get("path"),payload.getValue().get("path"));
        verify(memories).propose(any(),any(),any(),any(),any(),any());
    }
    @Test void wrongNestedMapShapeReportsSafeSchemaPathAndStopsAfterTwoCorrections() {
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response(
            "{\"node\":{\"inputs\":{\"secret-looking-key\":{\"source\":\"context\",\"value\":\"private-value\",\"unexpected-secret-field\":true}}}}","fixture"));
        var failure=assertThrows(FlowFailure.class,()->service.suggest(request("create")));
        assertEquals(FlowFailure.Code.MODEL_RESPONSE_INVALID,failure.code);
        assertTrue(failure.getMessage().contains("Patch.node.inputs[*]"));
        var payload=ArgumentCaptor.forClass(Map.class);verify(fixture.llm,times(3)).step(any(),payload.capture(),any());
        for(var value:payload.getAllValues()) {
            assertFalse(canonical(value).contains("secret-looking-key"));
            assertFalse(canonical(value).contains("unexpected-secret-field"));
            assertFalse(canonical(value).contains("private-value"));
        }
        verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
    }
    @Test void formatAndBindingCorrectionsHaveIndependentBoundedBudgets() {
        var good=fixture.llm.step("fixture",Map.of(),null);
        respond("create",new Binding("context","unknown"),List.of(),List.of());
        var badBinding=fixture.llm.step("fixture",Map.of(),null); clearInvocations(fixture.llm);
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response("not json","fixture"),badBinding,good);
        var result=service.suggest(request("create"));
        assertEquals("PENDING",result.memory().state()); verify(fixture.llm,times(3)).step(any(),any(),any());
        verify(memories).propose(any(),any(),any(),any(),any(),any());
    }
    @Test void modelTransportFailuresAreNotRetriedAsFormatErrors() {
        when(fixture.llm.step(any(),any(),any())).thenThrow(new FlowFailure(FlowFailure.Code.MODEL_TIMEOUT));
        assertEquals(FlowFailure.Code.MODEL_TIMEOUT,assertThrows(FlowFailure.class,()->service.suggest(request("create"))).code);
        verify(fixture.llm).step(any(),any(),any());verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
    }

    @Test void sensitiveHeaderLiteralIsRejectedAndCorrectedWithoutReplayingCredential() {
        var good=fixture.llm.step("fixture",Map.of(),null);clearInvocations(fixture.llm);
        var plan=new io.yuan.octopus.preparation.PreparationPlan(List.of(new io.yuan.octopus.preparation.PreparationPlan.Call("req","uuid","1",Map.of())),null,Map.of("Authorization",new io.yuan.octopus.preparation.PreparationPlan.Value("literal","private-bearer-token")));
        var fill=new FlowSuggestionService.NodeFill("create",Map.of(),List.of(),plan);
        var bad=new FlowModelClient.Response(canonical(new StepSuggestionService.Patch(fill,List.of(),new StepMemoryService.MemoryDraft("说明",List.of(),List.of(),List.of()),List.of(),List.of())),"fixture");
        when(fixture.llm.step(any(),any(),any())).thenReturn(bad,good);
        var result=service.suggest(request("create"));
        var payload=ArgumentCaptor.forClass(Map.class);verify(fixture.llm,times(2)).step(any(),payload.capture(),any());
        assertTrue(canonical(payload.getValue().get("patchFeedback")).contains("敏感请求头不能保存字面量"));
        assertFalse(canonical(payload.getValue()).contains("private-bearer-token"));
        assertFalse(canonical(result).contains("private-bearer-token"));
        verify(memories).propose(any(),any(),any(),any(),any(),any());
    }

    @Test void preparationContextDiagnosticsIdentifyParameterAndExcludeOwnOutputs() {
        var plan=new io.yuan.octopus.preparation.PreparationPlan(List.of(
            new io.yuan.octopus.preparation.PreparationPlan.Call("join","string.concat","1",Map.of(
                "prefix",new io.yuan.octopus.preparation.PreparationPlan.Value("literal","Bearer "),
                "value",new io.yuan.octopus.preparation.PreparationPlan.Value("context","private-unknown-reference")))) ,null,Map.of());
        var fill=new FlowSuggestionService.NodeFill("create",Map.of(),List.of(new Output("own.id","$.body.id","string",false)),plan);
        when(fixture.llm.step(any(),any(),any())).thenReturn(new FlowModelClient.Response(canonical(new StepSuggestionService.Patch(fill,List.of(),
            new StepMemoryService.MemoryDraft("说明",List.of(),List.of(),List.of()),List.of(),List.of())),"fixture"));
        var failure=assertThrows(FlowFailure.class,()->service.suggest(request("create")));
        var issue=failure.detail("GENERATING","create").bindingIssue();
        assertEquals("preparation.tools[0].inputs.value",issue.input());
        assertEquals(List.of("test.user"),issue.availableContextKeys());
        assertEquals("CONTEXT_UNDECLARED",issue.code());
        assertFalse(failure.getMessage().contains("private-unknown-reference"));
        var payload=ArgumentCaptor.forClass(Map.class);verify(fixture.llm,times(2)).step(any(),payload.capture(),any());
        assertEquals(issue,payload.getValue().get("validationFeedback"));
        verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
    }

    @Test void identicalExistingOutputEchoIsANoopButConflictingDefinitionIsRejected() {
        var f=fixture.flow;var n=f.nodes().getFirst();var output=new Output("order.id","$.body.id","string",true);
        fixture.flow=new Flow(f.flowId(),f.revision(),f.name(),f.initialContext(),List.of(
            new FlowNode(n.id(),n.label(),n.operationRef(),n.inputs(),List.of(output),n.states(),n.waitMillis()),f.nodes().get(1)),f.dependencies(),f.stateModels(),f.testPoints());
        respond("create",new Binding("context","test.user"),List.of(output,output),List.of());
        var result=service.suggest(request("create"));
        assertEquals(List.of(output),result.flow().nodes().getFirst().outputs());
        clearInvocations(memories);
        respond("create",new Binding("context","test.user"),List.of(new Output("order.id","$.body.id","string",false)),List.of());
        assertThrows(FlowFailure.class,()->service.suggest(request("create")));
        verify(memories,never()).propose(any(),any(),any(),any(),any(),any());
    }

    @Test void allModelRoundsShareOneInteractiveDeadline() {
        // 评审 P2：工具发现与格式修正是串行的多轮调用；每轮超时取剩余时间，耗尽时以 MODEL_TIMEOUT 结束
        var bad=new FlowModelClient.Response("not json","fixture");
        when(fixture.llm.step(any(),any(),any())).thenReturn(bad);
        service.interactiveBudgetSeconds=240;
        assertThrows(FlowFailure.class,()->service.suggest(request("create")));
        var timeouts=ArgumentCaptor.forClass(java.time.Duration.class);
        verify(fixture.llm,atLeastOnce()).step(any(),any(),timeouts.capture());
        assertTrue(timeouts.getAllValues().stream().allMatch(t->t.toSeconds()<=230),"every round fits inside the 240 s budget minus the response margin");
        clearInvocations(fixture.llm);
        service.interactiveBudgetSeconds=20;
        var error=assertThrows(FlowFailure.class,()->service.suggest(request("create")));
        assertEquals(FlowFailure.Code.MODEL_TIMEOUT,error.code);
        verify(fixture.llm,never()).step(any(),any(),any());
    }
}
