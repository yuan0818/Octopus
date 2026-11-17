package io.yuan.octopus.scenario.testing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;

class PollChecksTest {

    private static Step step(Poll poll) {
        return new Step("s1", "op@v1", Map.of(), List.of(), List.of(),
                List.of(new Assertion("st", "STATUS_EQUALS", null, 200)), 0, null, poll);
    }

    @Test
    void acceptsBoundedPolling() {
        assertDoesNotThrow(() -> PollChecks.validate(step(new Poll(2000, 60000)), Set.of(PollChecks.CAPABILITY)));
        assertDoesNotThrow(() -> PollChecks.validate(step(null), Set.of()));
        assertEquals(31, PollChecks.attempts(new Poll(2000, 60000)));
    }

    @Test
    void rejectsOutOfRangeOrMissingCapability() {
        assertThrows(IllegalArgumentException.class, () -> PollChecks.validate(step(new Poll(2000, 60000)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> PollChecks.validate(step(new Poll(100, 60000)), Set.of(PollChecks.CAPABILITY)));
        assertThrows(IllegalArgumentException.class, () -> PollChecks.validate(step(new Poll(2000, 600_000)), Set.of(PollChecks.CAPABILITY)));
        assertThrows(IllegalArgumentException.class, () -> PollChecks.validate(step(new Poll(5000, 2000)), Set.of(PollChecks.CAPABILITY)));
        assertThrows(IllegalArgumentException.class, () -> PollChecks.validate(step(new Poll(200, 300_000)), Set.of(PollChecks.CAPABILITY)));
    }

    @Test
    void pollIsOmittedFromCanonicalJsonWhenAbsent() {
        // 旧流程 / IR 的 hash 不能因为新增可选字段而改变
        String json = TestingJson.canonical(step(null));
        assertFalse(json.contains("poll"));
        // canonical() 会把整数规范成 5E+2 这类形式（已写入既有 hash，不能改），因此按数值比较而非字符串
        var poll = com.google.gson.JsonParser.parseString(TestingJson.canonical(step(new Poll(500, 5000))))
                .getAsJsonObject().getAsJsonObject("poll");
        assertEquals(500, poll.get("intervalMillis").getAsLong());
        assertEquals(5000, poll.get("timeoutMillis").getAsLong());
        assertFalse(poll.has("authorizationRef"));
    }
}
