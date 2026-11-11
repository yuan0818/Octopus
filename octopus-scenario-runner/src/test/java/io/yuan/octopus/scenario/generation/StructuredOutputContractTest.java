package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.scenario.testing.FlowModelClient;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * 结构化输出的两端契约（SO-006）：review-agent 还原后的内容（与 review-agent StructuredOutputTest 中的字符串逐字相同）
 * 必须能被现有的严格读取接受；格式无效的响应在进入修正前各记录一次（SO-010）。
 */
class StructuredOutputContractTest {
    static final String PLAN_NEW_NORMALIZED = "{\"intents\":[{\"title\":\"数量为 0\",\"category\":\"NEGATIVE\",\"callId\":\"create\","
            + "\"overrides\":{\"quantity\":-1},\"data\":{\"login\":{\"region\":\"cn\"}},\"slots\":{\"create\":{\"coupon\":{\"purpose\":\"VALID\",\"uniqueKey\":false}}},"
            + "\"rejectionStatus\":400,\"assertions\":[{\"kind\":\"JSON_EQUALS\",\"path\":\"$.code\",\"expected\":\"INVALID_QUANTITY\"}],"
            + "\"rationale\":\"违反 minimum\",\"evidence\":[\"minimum=1\"]}],\"questions\":[\"是否需要校验库存\"]}";
    static final String PLAN_REVISE_NORMALIZED = "{\"intents\":[{\"intentId\":\"it_1\",\"data\":{\"create\":{\"sku\":\"SKU-2\"}},\"slots\":{}},"
            + "{\"parentIntentId\":\"it_2\",\"title\":\"拆分：库存不足\",\"preconditionNotes\":\"需要库存为 0 的商品\"}],"
            + "\"drops\":[{\"intentId\":\"it_3\",\"reason\":\"无法修复\"}],\"changes\":[{\"intentId\":\"it_1\",\"feedbackIds\":[\"fb_1\"],\"description\":\"换 SKU\"}],"
            + "\"questions\":[]}";
    static final String MATERIALIZE_NORMALIZED = "{\"bindings\":{\"create\":{\"coupon\":{\"source\":\"literal\",\"value\":\"CP-1234\"},\"region\":{\"source\":\"context\",\"value\":\"run.region\"}}}}";

    @Test
    void normalizedResponsesPassTheExistingStrictReader() {
        var plan = ModelIntentSource.parseJson(PLAN_NEW_NORMALIZED, ModelIntentSource.PlanOutput.class);
        var intent = plan.intents().getFirst();
        assertEquals(0, new BigDecimal(-1).compareTo(new BigDecimal(intent.overrides().get("quantity").toString())));
        assertEquals("VALID", intent.slots().get("create").get("coupon").purpose());
        assertNull(intent.preconditionNotes());
        assertNull(intent.intentionallyViolatedRule());

        var revision = ModelIntentSource.parseJson(PLAN_REVISE_NORMALIZED, ModelIntentSource.PlanOutput.class);
        assertEquals("it_1", revision.intents().get(0).intentId());
        assertTrue(revision.intents().get(0).slots().isEmpty(), "[] clears the slots");
        assertNull(revision.intents().get(1).data(), "null means unchanged");
        assertNull(revision.intents().get(0).callId(), "no immutable field reaches the merge");

        var bindings = ModelIntentSource.parseJson(MATERIALIZE_NORMALIZED, DataSlotFiller.MaterializeOutput.class).bindings();
        assertEquals("CP-1234", bindings.get("create").get("coupon").value());
        assertEquals("context", bindings.get("create").get("region").source());
    }

    @Test
    void eachFormatInvalidResponseIsRecordedOnceBeforeCorrection() {
        var models = mock(FlowModelClient.class);
        when(models.casePlan(any(), any(), any())).thenReturn(
                new FlowModelClient.VersionedResponse("{\"intents\":[{\"title\":\"截断", "m", "case-plan@1+schema@1"),
                new FlowModelClient.VersionedResponse("{\"intents\":[]}", "m", "case-plan@1+schema@1"));
        var flow = GenerationFixtures.flow(false);
        new ModelIntentSource(models).plan(new ModelIntentSource.Context(flow, "h", GenerationFixtures.happy(), GenerationFixtures.operations(),
                null, 5, null, "qwen-schema", Set.of()));
        verify(models, times(1)).recordFormatInvalid("CASE_PLAN", "qwen-schema", "case-plan@1+schema@1");

        when(models.caseMaterialize(any(), any(), any())).thenReturn(
                new FlowModelClient.VersionedResponse("不是 JSON", "m", "case-materialize@1"),
                new FlowModelClient.VersionedResponse("{\"bindings\":{\"create\":{\"quantity\":{\"source\":\"literal\",\"value\":7}}}}", "m", "case-materialize@1"));
        var base = RuleIntentSource.plan(flow, GenerationFixtures.happy(), GenerationFixtures.operations(), null, Set.of()).intents().getFirst();
        var intent = base.withMutable(base.dataPlan(), Map.of("create", Map.of("quantity",
                new Slot("integer", "VALID", Map.of("pattern", "^[0-9]+$"), false))), null, List.of());
        var fill = new DataSlotFiller(models, null).fill(intent, flow, GenerationFixtures.operations(), "deepseek-json");
        assertEquals(7, ((Number) fill.bindings().get("create").get("quantity").value()).intValue());
        verify(models, times(1)).recordFormatInvalid("CASE_MATERIALIZE", "deepseek-json", "case-materialize@1");
    }
}
