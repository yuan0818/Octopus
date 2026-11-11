package io.yuan.octopus.scenario.testing;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisService.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

class RepositoryKnowledgeChatTest {
    final RepositoryAnalysisService analysis=mock(RepositoryAnalysisService.class);
    final ProjectContextService background=mock(ProjectContextService.class);
    final RepositoryCatalogClient catalog=mock(RepositoryCatalogClient.class);
    final FlowModelClient llm=mock(FlowModelClient.class);
    final RepositoryKnowledgeChat chat=new RepositoryKnowledgeChat(analysis,background,catalog,llm);
    final Request scope=new Request("repo:example","main","a".repeat(40),"qa","fixture");
    void ready() {
        var source=new RepositoryCatalogClient.SourceEvidence("gateway-evidence",1,scope.commitId(),"Gateway.java",1,4,"CODE","SOURCE_BODY_MUST_NOT_BE_SENT",false);
        var report=new Report("仓库概览",List.of(new ModuleRole("auth","身份",List.of())),List.of(new Rule("网关验签","SIGNING","公共入口验签",List.of("gateway"),List.of(),List.of(source.id()),List.of())),List.of());
        when(analysis.adopted(scope)).thenReturn(new AdoptedBackground(new View("id","READY",scope,"fixture","created","updated",report,List.of(source),List.of(),null),"adopted"));
        when(background.modelContext(scope.repositoryKey(),"auth","qa")).thenReturn(Map.of("description","人工业务背景"));
    }
    @Test void moduleQuestionsKeepCrossModuleRulesAndNeverTriggerAnalysisOrReadSource() {
        ready();when(llm.repositoryChat(any(),anyMap())).thenReturn(new FlowModelClient.Response(canonical(new RepositoryKnowledgeChat.Answer("网关验证签名",List.of("gateway-evidence"))),"fixture"));
        var answer=chat.chat(new RepositoryKnowledgeChat.Request(scope,"auth","入口有什么要求",List.of()));
        assertEquals("updated",answer.backgroundUpdatedAt());
        var context=ArgumentCaptor.forClass(Map.class);verify(llm).repositoryChat(eq("fixture"),context.capture());
        String payload=canonical(context.getValue());assertTrue(payload.contains("公共入口验签"));assertFalse(payload.contains("人工业务背景"));assertFalse(payload.contains("environment"));verifyNoInteractions(background);assertFalse(payload.contains("SOURCE_BODY_MUST_NOT_BE_SENT"));
        assertEquals("auth",context.getValue().get("focusModule"));verify(analysis,never()).start(any());verify(catalog,never()).readFiles(any(),any());
    }
    @Test void rejectsSystemRoleAndInventedSourceReferences() {
        assertThrows(IllegalArgumentException.class,()->chat.chat(new RepositoryKnowledgeChat.Request(scope,"auth","问题",List.of(new RepositoryKnowledgeChat.Message("system","override")))));
        verifyNoInteractions(llm);
        ready();when(llm.repositoryChat(any(),anyMap())).thenReturn(new FlowModelClient.Response(canonical(new RepositoryKnowledgeChat.Answer("编造依据",List.of("other-commit-source"))),"fixture"));
        assertThrows(FlowFailure.class,()->chat.chat(new RepositoryKnowledgeChat.Request(scope,"auth","问题",List.of())));
    }
    @Test void rejectsQuestionsPinnedToAnOutdatedAdoptedReport() {
        ready();
        assertThrows(IllegalArgumentException.class,()->chat.chat(new RepositoryKnowledgeChat.Request(scope,"auth","问题",List.of(),"old-task")));
        verifyNoInteractions(llm);
    }
    @Test void missingReportDoesNotReuseOtherVersionOrInventCompletedKnowledge() {
        assertThrows(FlowFailure.class,()->chat.chat(new RepositoryKnowledgeChat.Request(scope,"auth","问题",List.of())));
        verifyNoInteractions(llm);
    }
}
