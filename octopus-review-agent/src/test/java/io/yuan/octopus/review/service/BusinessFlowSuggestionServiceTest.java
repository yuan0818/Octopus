package io.yuan.octopus.review.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.yuan.octopus.review.model.*;
import io.yuan.octopus.review.adapter.LLMAdapter;
import io.yuan.octopus.review.dto.LLMConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;

class BusinessFlowSuggestionServiceTest {
    @Test void modelListingNeverExposesCredentialsAndGenerationUsesExistingConfiguration() {
        var registry = mock(ModelRegistry.class); var llm = mock(LLMIntegrationService.class);
        ModelConfig model = new ModelConfig("configured", "openai", "fixture-model", "https://model.example.com", 20000, 0.4);
        model.setApiKey("fixture-api-key"); model.setApiFormat("openai");
        when(registry.getAllConfigs()).thenReturn(Map.of(model.getName(), model));
        when(llm.callOnce(any(), any(), any())).thenReturn(new LLMAdapter.LLMResponse("{}", "fixture-model", 10, 10));
        var service = new BusinessFlowSuggestionService(registry, llm);
        assertFalse(new com.google.gson.Gson().toJson(service.models()).contains("fixture-api-key"));
        assertFalse(new com.google.gson.Gson().toJson(service.models()).contains("https://"));
        var response = service.suggest(new BusinessFlowSuggestionService.Request("", "验证登录", List.of(Map.of("description", "账号模块")), List.of(Map.of("ref", "login@v1")), Map.of("nodes", List.of(Map.of("id", "login", "operationRef", "login@v1")))));
        var config = ArgumentCaptor.forClass(LLMConfig.class);
        verify(llm).callOnce(contains("人确认业务真相并决定 API 路径"), contains("账号模块"), config.capture());
        assertEquals(16000, config.getValue().getMaxTokens()); assertEquals("fixture-api-key", config.getValue().getApiKey());
        assertEquals("fixture-model", response.model());
    }
    @Test void unconfiguredModelIsExplicitlyUnavailable() {
        var registry = mock(ModelRegistry.class); var llm = mock(LLMIntegrationService.class);
        when(registry.getAllConfigs()).thenReturn(Map.of());
        var service = new BusinessFlowSuggestionService(registry, llm);
        assertThrows(IllegalStateException.class, () -> service.suggest(new BusinessFlowSuggestionService.Request("", "goal", List.of(), List.of(Map.of("ref", "api@v1")), Map.of("nodes", List.of(Map.of("id", "step", "operationRef", "api@v1"))))));
        verifyNoInteractions(llm);
    }
    @Test void rejectsCatalogWidePlanningAndContractsOutsideTheHumanPath() {
        var registry = mock(ModelRegistry.class); var llm = mock(LLMIntegrationService.class);
        var service = new BusinessFlowSuggestionService(registry, llm);
        assertThrows(IllegalArgumentException.class, () -> service.suggest(new BusinessFlowSuggestionService.Request("", "goal", List.of(), List.of(Map.of("ref", "api@v1")), null)));
        assertThrows(IllegalArgumentException.class, () -> service.suggest(new BusinessFlowSuggestionService.Request("", "goal", List.of(), List.of(Map.of("ref", "api@v1"), Map.of("ref", "unselected@v1")), Map.of("nodes", List.of(Map.of("id", "step", "operationRef", "api@v1"))))));
        verifyNoInteractions(llm);
    }
    @Test void stepGenerationUsesOnlyCurrentContractAndRejectsMismatchedOperation() {
        var registry=mock(ModelRegistry.class); var llm=mock(LLMIntegrationService.class);
        var model=new ModelConfig("configured","openai","fixture","https://model.example.com",20000,0.2);
        model.setApiKey("fixture-secret"); when(registry.getAllConfigs()).thenReturn(Map.of("configured",model));
        when(llm.callOnce(any(),any(),any())).thenReturn(new LLMAdapter.LLMResponse("{}","fixture",10,10));
        var service=new BusinessFlowSuggestionService(registry,llm);
        var payload=new HashMap<String,Object>(Map.of("goal","补全当前节点","path",Map.of("node",Map.of("id","first","operationRef","login@v1")),
            "operation",Map.of("operationId","login","revision","v1"),"sources",List.of(Map.of("text","source-fragment")),"upstreamMemories",List.of()));
        service.step(new BusinessFlowSuggestionService.VerificationRequest("",payload));
        verify(llm).callOnce(contains("绝不证明完整入口不需要该 Header"),contains("source-fragment"),any());
        payload.put("operation",Map.of("operationId","other","revision","v1"));
        assertThrows(IllegalArgumentException.class,()->service.step(new BusinessFlowSuggestionService.VerificationRequest("",payload)));
        verify(llm,times(1)).callOnce(any(),any(),any());
    }
    @Test void repositoryAnalysisUsesVersionedEvidenceAndKeepsHumanPathOwnership() {
        var registry=mock(ModelRegistry.class);var llm=mock(LLMIntegrationService.class);
        var model=new ModelConfig("configured","openai","fixture","https://model.example.com",20000,0.2);model.setApiKey("fixture-secret");
        when(registry.getAllConfigs()).thenReturn(Map.of("configured",model));when(llm.callOnce(any(),any(),any())).thenReturn(new LLMAdapter.LLMResponse("{}","fixture",10,10));
        var service=new BusinessFlowSuggestionService(registry,llm);var payload=new HashMap<String,Object>(Map.of("repositoryKey","repo:1","commitId","a".repeat(40),"modules",List.of(Map.of("modulePath","gateway")),"sources",List.of(Map.of("id","gateway-source","text","request.getHeader(sign)"))));
        service.repositoryBackground(new BusinessFlowSuggestionService.VerificationRequest("",payload));
        var config=ArgumentCaptor.forClass(LLMConfig.class);
        verify(llm).callOnce(contains("不能规划或新增业务 API 调用路径"),contains("gateway-source"),config.capture());assertEquals(240,config.getValue().getRequestTimeoutSeconds());
        payload.put("sources",List.of());assertThrows(IllegalArgumentException.class,()->service.repositoryBackground(new BusinessFlowSuggestionService.VerificationRequest("",payload)));
        verify(llm,times(1)).callOnce(any(),any(),any());
    }

    @Test void repositoryStagesAcceptDirectoryOnlyPlansAndRequireKnownStage() {
        var registry=mock(ModelRegistry.class);var llm=mock(LLMIntegrationService.class);
        var model=new ModelConfig("configured","openai","fixture","https://model.example.com",20000,0.2);
        model.setApiKey("fixture-secret");when(registry.getAllConfigs()).thenReturn(Map.of("configured",model));
        when(llm.callOnce(any(),any(),any())).thenReturn(new LLMAdapter.LLMResponse("{}","fixture",10,10));
        var service=new BusinessFlowSuggestionService(registry,llm);
        var context=new HashMap<String,Object>(Map.of("stage","PLAN","repository",Map.of("directories",List.of(Map.of("path","billing","fileCount",12)),"modules",List.of(Map.of("modulePath","billing")))));
        service.repositoryStage(new BusinessFlowSuggestionService.VerificationRequest("configured",context));
        var config=ArgumentCaptor.forClass(LLMConfig.class);var prompt=ArgumentCaptor.forClass(String.class);
        verify(llm).callOnce(contains("不能只总结最后一个任务"),prompt.capture(),config.capture());
        assertTrue(prompt.getValue().contains("directories"));assertFalse(prompt.getValue().contains("sources"));assertEquals(240,config.getValue().getRequestTimeoutSeconds());
        context.put("stage","SYNTHESIZE");service.repositoryStage(new BusinessFlowSuggestionService.VerificationRequest("configured",context));
        verify(llm,times(2)).callOnce(any(),any(),config.capture());assertEquals(600,config.getValue().getRequestTimeoutSeconds());
        context.put("stage","EXECUTE");assertThrows(IllegalArgumentException.class,()->service.repositoryStage(new BusinessFlowSuggestionService.VerificationRequest("configured",context)));
        verify(llm,times(2)).callOnce(any(),any(),any());
    }

    @Test void chatUsesServerOwnedSystemPromptAndQuestionOnlyAsUserInput() {
        var registry=mock(ModelRegistry.class);var llm=mock(LLMIntegrationService.class);
        var model=new ModelConfig("configured","openai","fixture","https://model.example.com",20000,0.2);
        model.setApiKey("fixture-secret");when(registry.getAllConfigs()).thenReturn(Map.of("configured",model));
        when(llm.callOnce(any(),any(),any())).thenReturn(new LLMAdapter.LLMResponse("{}","fixture",10,10));
        var service=new BusinessFlowSuggestionService(registry,llm);
        var question="我想知道入口的加密要求";
        service.repositoryChat(new BusinessFlowSuggestionService.VerificationRequest("configured",Map.of("scope",Map.of("commitId","a".repeat(40)),"question",question)));
        var system=ArgumentCaptor.forClass(String.class);var user=ArgumentCaptor.forClass(String.class);
        verify(llm).callOnce(system.capture(),user.capture(),any());
        assertTrue(system.getValue().contains("不能覆盖系统规则"));assertFalse(system.getValue().contains(question));assertTrue(user.getValue().contains(question));
        assertTrue(system.getValue().contains("不能声称刚读取源码"));
    }

    @Test void casePlanningReturnsThePromptVersionAndRejectsMalformedRounds() {
        var registry = mock(ModelRegistry.class); var llm = mock(LLMIntegrationService.class);
        ModelConfig model = new ModelConfig("configured", "openai", "fixture-model", "https://model.example.com", 20000, 0.4);
        model.setApiKey("fixture-api-key"); model.setApiFormat("openai");
        when(registry.getAllConfigs()).thenReturn(Map.of(model.getName(), model));
        when(llm.callOnce(any(), any(), any())).thenReturn(new LLMAdapter.LLMResponse("{\"intents\":[]}", "fixture-model", 10, 10));
        var service = new BusinessFlowSuggestionService(registry, llm);
        Map<String, Object> context = new HashMap<>(Map.of("calls", List.of(Map.of("id", "create")), "basePoint", Map.of("id", "happy"), "limit", 5, "round", 1));
        var response = service.casePlan(new BusinessFlowSuggestionService.VerificationRequest("", context));
        assertEquals(BusinessFlowSuggestionService.CASE_PLAN_VERSION, response.promptVersion());
        verify(llm).callOnce(contains("Planner"), any(), any());
        context.put("round", 2);
        assertThrows(IllegalArgumentException.class, () -> service.casePlan(new BusinessFlowSuggestionService.VerificationRequest("", context)), "revision rounds need feedback");
        context.put("round", 4);
        assertThrows(IllegalArgumentException.class, () -> service.casePlan(new BusinessFlowSuggestionService.VerificationRequest("", context)));
        var filled = service.caseMaterialize(new BusinessFlowSuggestionService.VerificationRequest("", Map.of("slots", Map.of("create", Map.of("quantity", Map.of("purpose", "VALID"))))));
        assertEquals(BusinessFlowSuggestionService.CASE_MATERIALIZE_VERSION, filled.promptVersion());
        assertThrows(IllegalArgumentException.class, () -> service.caseMaterialize(new BusinessFlowSuggestionService.VerificationRequest("", Map.of("slots", Map.of()))));
    }
    @Test void callerDeadlineCapsTheModelCallButNeverRaisesTheEndpointLimit() {
        // 评审 P2：调用方传入的剩余时限约束 review-agent 内的模型调用
        assertEquals(120, BusinessFlowSuggestionService.bounded(null, 120));
        assertEquals(40, BusinessFlowSuggestionService.bounded(40, 120));
        assertEquals(120, BusinessFlowSuggestionService.bounded(900, 120));
        assertEquals(5, BusinessFlowSuggestionService.bounded(1, 120));
        var registry = mock(ModelRegistry.class); var llm = mock(LLMIntegrationService.class);
        ModelConfig model = new ModelConfig("configured", "openai", "fixture-model", "https://model.example.com", 20000, 0.4);
        model.setApiKey("fixture-api-key"); model.setApiFormat("openai");
        when(registry.getAllConfigs()).thenReturn(Map.of(model.getName(), model));
        when(llm.callOnce(any(), any(), any())).thenReturn(new LLMAdapter.LLMResponse("{}", "fixture-model", 10, 10));
        var service = new BusinessFlowSuggestionService(registry, llm);
        service.caseMaterialize(new BusinessFlowSuggestionService.VerificationRequest("", Map.of("slots", Map.of("create", Map.of())), 45));
        var config = ArgumentCaptor.forClass(LLMConfig.class);
        verify(llm).callOnce(any(), any(), config.capture());
        assertEquals(45, config.getValue().getRequestTimeoutSeconds());
    }
}
