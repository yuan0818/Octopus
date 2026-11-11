package io.yuan.octopus.scenario.testing;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StepModelJsonTest {
    private static final String VALID="""
        {"node":{"id":"login","inputs":{},"outputs":[],"preparation":null},"assertions":[],
        "memory":{"summary":"登录","decisions":[],"questions":[],"evidenceIds":[]},"assumptions":[],"questions":[]}
        """;
    private StepSuggestionService.Patch parse(String text){return StepModelJson.parse(new FlowModelClient.Response(text,"test"));}
    @Test void acceptsFullJsonAndMarkdownFenceWithoutChangingContract() {
        assertEquals("login",parse(VALID).node().id());
        assertEquals("login",parse("```JSON\n"+VALID+"\n```").node().id());
    }
    @Test void missingAndNullListsHaveActionableDiagnostics() {
        assertTrue(assertThrows(FlowFailure.class,()->parse(VALID.replace("\"assertions\":[]", "\"assertions\":null"))).getMessage().contains("Patch.assertions"));
        assertTrue(assertThrows(FlowFailure.class,()->parse(VALID.replace("\"questions\":[]", "\"questions\":[null]"))).getMessage().contains("数组不能含 null"));
    }
    @Test void distinguishesSyntaxShapeAndPrimitiveErrorsWithoutEchoingValues() {
        assertTrue(assertThrows(FlowFailure.class,()->parse(VALID+" explanation")).getMessage().contains("JSON_SYNTAX"));
        assertTrue(assertThrows(FlowFailure.class,()->parse(VALID.replace("\"outputs\":[]", "\"outputs\":{}"))).getMessage().contains("Patch.node.outputs 应为数组"));
        var failure=assertThrows(FlowFailure.class,()->parse(VALID.replace("\"outputs\":[]", "\"outputs\":[{\"sensitive\":\"private-token\"}]")));
        assertTrue(failure.getMessage().contains("sensitive 应为布尔值"));assertFalse(failure.getMessage().contains("private-token"));
        assertTrue(assertThrows(FlowFailure.class,()->parse("[]")).getMessage().contains("根节点应为对象"));
    }
}
