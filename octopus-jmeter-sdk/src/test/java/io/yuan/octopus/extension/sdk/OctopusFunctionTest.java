package io.yuan.octopus.extension.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.extension.sample.functions.v1_0_0.SampleJoin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OctopusFunctionTest {
    @Test
    void referenceKeyAndArgumentDescriptionsComeFromAnnotations() {
        var f = new SampleJoin();
        assertEquals("__sample_join_v1_0_0", f.getReferenceKey());
        assertEquals(3, f.getArgumentDesc().size());
    }

    @Test
    void convertsDefaultsAndMapsErrors() {
        var f = new SampleJoin();
        assertEquals("a-b", f.invoke(List.of("a", "b")));
        assertEquals("a:b", f.invoke(List.of("a", "b", ":")));
        assertEquals(ToolNames.error("JOIN_EMPTY"), f.invoke(List.of("", "b")));
        assertEquals(ToolNames.error(OctopusFunction.ARGUMENT_INVALID), f.invoke(List.of("x".repeat(101), "b")));
        assertEquals(ToolNames.error(OctopusFunction.ARGUMENT_INVALID), f.invoke(List.of("a")));
    }

    @Test
    void jsonRoundTrip() {
        Object parsed = Json.parse("{\"a\":[1,2.50,true,null],\"b\":\"x\\\"y\"}");
        assertTrue(parsed instanceof Map);
        assertEquals("{\"a\":[1,2.5,true,null],\"b\":\"x\\\"y\"}", Json.write(parsed));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void namingRules() {
        assertTrue(ToolNames.NAME.matcher("acme.sign").matches());
        assertFalse(ToolNames.NAME.matcher("Acme").matches());
        assertEquals("__acme_sign_v2_0_1", ToolNames.referenceKey("acme.sign", "2.0.1"));
        assertEquals("JOIN_EMPTY", ToolNames.errorCode(ToolNames.error("JOIN_EMPTY")));
    }
}
