package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.scenario.extension.FunctionCatalogService;
import io.yuan.octopus.scenario.testing.FlowModelClient;
import java.util.*;
import org.junit.jupiter.api.Test;

/** OPER-002、OPER-005 / AT-G10：数据位只能填 literal／context；未登记工具与超出预算使候选静态无效。 */
class DataSlotFillerTest {
    final Flow flow = GenerationFixtures.flow(false);
    final Map<String, Operation> ops = GenerationFixtures.operations();
    final FlowModelClient models = mock(FlowModelClient.class);
    final FunctionCatalogService catalog = mock(FunctionCatalogService.class);

    TestIntent intent(Map<String, Map<String, Slot>> slots) {
        var base = RuleIntentSource.plan(flow, GenerationFixtures.happy(), ops, null, Set.of()).intents().getFirst();
        return base.withMutable(base.dataPlan(), slots, null, List.of());
    }

    FlowModelClient.VersionedResponse reply(String content) { return new FlowModelClient.VersionedResponse(content, "m", "case-materialize@1"); }

    @Test
    void deterministicValuesNeedNoModel() {
        var fill = new DataSlotFiller(models, catalog).fill(intent(Map.of("create", Map.of("quantity",
                new Slot("integer", "VALID", Map.of("minimum", 3, "maximum", 9, "multipleOf", 2), false)))), flow, ops, "m");
        assertEquals(new java.math.BigDecimal(4), fill.bindings().get("create").get("quantity").value());
        verifyNoInteractions(models);
    }

    @Test
    void modelBindingsAreRestrictedToTheOpenSlotsAndSafeSources() {
        when(models.caseMaterialize(any(), any(), any())).thenReturn(reply("""
                {"bindings":{"create":{"quantity":{"source":"literal","value":7},"token":{"source":"literal","value":"Bearer x"}},
                 "login":{"body":{"source":"secret","value":"x"}}}}"""));
        var fill = new DataSlotFiller(models, catalog).fill(intent(Map.of("create", Map.of("quantity",
                new Slot("integer", "VALID", Map.of("pattern", "^[0-9]+$"), false)))), flow, ops, "m");
        assertEquals(7, ((Number) fill.bindings().get("create").get("quantity").value()).intValue());
        assertFalse(fill.bindings().get("create").containsKey("token"), "inputs outside the slots are ignored");
        assertTrue(fill.issues().stream().anyMatch(i -> i.contains("数据位以外")));
        assertEquals("case-materialize@1", fill.promptVersion());
    }

    @Test
    void unknownToolsAndExhaustedBudgetsMakeTheCandidateStaticallyInvalid() {
        when(catalog.search(any(), any(), any())).thenReturn(List.of());
        when(models.caseMaterialize(any(), any(), any())).thenReturn(reply("{\"toolCalls\":[{\"name\":\"run_shell\",\"arguments\":{}},{\"name\":\"search_functions\",\"arguments\":{\"query\":\"uuid\"}}]}"));
        var fill = new DataSlotFiller(models, catalog).fill(intent(Map.of("create", Map.of("quantity",
                new Slot("integer", "VALID", Map.of("format", "custom"), false)))), flow, ops, "m");
        assertTrue(fill.issues().stream().anyMatch(i -> i.contains("未登记的工具 run_shell")));
        assertTrue(fill.issues().stream().anyMatch(i -> i.contains("超出预算")));
        // 物化后由静态校验判为 STATIC_INVALID，且路由给 Planner
        var m = IntentMaterializer.materialize(intent(Map.of("create", Map.of("quantity", new Slot("integer", "VALID", Map.of(), false)))), "h", "happy", "t", 1, null, fill.bindings(), Set.of());
        var store = GenerationFixtures.store(flow);
        var issues = new ArrayList<>(fill.issues());
        issues.addAll(m.issues());
        var checked = new StaticChecks(GenerationFixtures.planner(store), store).check(GenerationFixtures.confirmed(flow), m.point(), issues, null);
        assertFalse(checked.result().ok());
        assertTrue(checked.result().materializationError());
    }
}
