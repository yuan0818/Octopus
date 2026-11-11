package io.yuan.octopus.scenario.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.yuan.octopus.scenario.testing.RepositoryAnalysisWorkflow.*;
class RepositoryModelJsonTest {
    FlowModelClient.Response response(String text){return new FlowModelClient.Response(text,"fixture");}
    @Test void acceptsOnlyCompleteObjectsAndHandlesCodeFences() {
        assertEquals("LIST",parse(response("```json\n{\"action\":\"LIST\",\"offset\":0}\n```"),Decision.class).action());
        for(String text:new String[]{"", "{\"action\":", "[]", "{\"action\":\"DONE\"} trailing", "{action:'DONE'}"}) {
            var error=assertThrows(FlowFailure.class,()->parse(response(text),Decision.class));
            assertEquals(FlowFailure.Code.MODEL_RESPONSE_INVALID,error.code);
        }
    }
    @Test void reportsKnownSchemaPathsWithoutEchoingPrivateModelTextOrUnknownFieldNames() {
        var wrong=assertThrows(FlowFailure.class,()->parse(response("{\"action\":\"DONE\",\"memory\":{\"modules\":\"PRIVATE_MODEL_VALUE\"}}"),Decision.class));
        assertTrue(wrong.getMessage().contains("Decision.memory.modules 应为数组"));assertFalse(wrong.getMessage().contains("PRIVATE_MODEL_VALUE"));
        var extra=assertThrows(FlowFailure.class,()->parse(response("{\"action\":\"DONE\",\"PRIVATE_FIELD\":\"PRIVATE_VALUE\"}"),Decision.class));
        assertTrue(extra.getMessage().contains("SCHEMA_FIELD"));assertFalse(extra.getMessage().contains("PRIVATE_"));
        var numeric=assertThrows(FlowFailure.class,()->parse(response("{\"action\":\"LIST\",\"offset\":0.5}"),Decision.class));
        assertTrue(numeric.getMessage().contains("整数"));
    }
}
