package io.yuan.octopus.scenario.testing;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderedFlowPlanningTest {
    @Test void draftPinsProvidedApiVersionsAndDoesNotPersistOrExecute() {
        var store=mock(BusinessKnowledgeStore.class);var model=mock(FlowModelClient.class);
        var operation=TestingFixtures.login();when(store.operation(operation.ref())).thenReturn(operation);
        when(model.planOrdered(any(),anyMap())).thenReturn(new FlowModelClient.Response("{\"name\":\"登录\",\"steps\":[{\"id\":\"login\",\"label\":\"登录\",\"mode\":\"SERIAL\",\"operationRefs\":[\""+operation.ref()+"\"],\"reason\":\"依据已提供登录契约\"}],\"assumptions\":[],\"questions\":[\"补充账号 fixture\"]}","fixture-model"));
        var candidate=new OrderedFlowPlanningService(store,model).generate(new OrderedFlowPlanningService.Request("登录",List.of(operation.ref()),"model"));
        assertEquals(operation.ref(),candidate.flow().steps().getFirst().calls().getFirst().operationRef());
        assertTrue(candidate.flow().dependencies().isEmpty());assertEquals(0,candidate.flow().revision());
        verify(store,never()).saveDraft(any());verify(store,never()).decide(any(),anyInt(),any(),any(),anyBoolean());
    }
    @Test void refusesHallucinatedApisAndParallelWrites() {
        var operation=TestingFixtures.login();var known=Map.of(operation.ref(),operation);
        for(var step:List.of(new OrderedFlowPlanningService.PlannedStep("s1","s","SERIAL",List.of("unknown@v1"),"why"),
                new OrderedFlowPlanningService.PlannedStep("s1","s","PARALLEL",List.of(operation.ref()),"why")))
            assertThrows(IllegalArgumentException.class,()->OrderedFlowPlanningService.materialize(new OrderedFlowPlanningService.Plan("Flow",List.of(step),List.of(),List.of()),"model",known));
    }
    @Test void formatRepairBudgetIsBounded() {
        var store=mock(BusinessKnowledgeStore.class);var model=mock(FlowModelClient.class);var operation=TestingFixtures.login();
        when(store.operation(operation.ref())).thenReturn(operation);when(model.planOrdered(any(),anyMap())).thenReturn(new FlowModelClient.Response("not JSON","model"));
        assertThrows(IllegalArgumentException.class,()->new OrderedFlowPlanningService(store,model).generate(new OrderedFlowPlanningService.Request("login",List.of(operation.ref()),"model")));
        verify(model,times(3)).planOrdered(any(),anyMap());
    }
}
