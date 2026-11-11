package io.yuan.octopus.scenario.generation;

import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.scenario.testing.TestingJson;
import java.util.*;
import org.junit.jupiter.api.Test;

/** OBS-005 / AT-G12：持久化前脱敏，敏感输出不留摘要。 */
class EvidenceRedactorTest {
    static final String CANARY = "canary-SECRET-4242";

    @Test
    void sensitivePathsNamesAndKnownSecretsAreMasked() {
        var line = Map.<String, Object>of("callId", "login", "status", "200",
                "assertions", List.of(
                        Map.of("id", "a1", "path", "$.token", "actual", "anything"),
                        Map.of("id", "a2", "path", "$.profile.sessionKey", "actual", "abc"),
                        Map.of("id", "a3", "path", "$.message", "actual", "hello " + CANARY + " world"),
                        Map.of("id", "a4", "path", "$.note", "actual", "x".repeat(400)),
                        Map.of("id", "a5", "path", "$.gone", "missing", true)),
                "outputs", List.of(
                        Map.of("key", "auth.token", "sha256", "a".repeat(64), "sensitive", true),
                        Map.of("key", "order.id", "sha256", "b".repeat(64), "sensitive", false)));
        var redacted = EvidenceRedactor.redact(List.of(line), Set.of("$.token"), List.of(CANARY, "short"));
        String json = TestingJson.canonical(redacted);
        assertFalse(json.contains(CANARY));
        assertFalse(json.contains("a".repeat(64)), "no digest of a secret value");
        assertTrue(json.contains("b".repeat(64)));
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) redacted.getFirst().get("assertions");
        assertEquals(EvidenceRedactor.MASK, rows.get(0).get("actual"));
        assertEquals(EvidenceRedactor.MASK, rows.get(1).get("actual"));
        assertEquals("hello *** world", rows.get(2).get("actual"));
        assertTrue(((String) rows.get(3).get("actual")).length() <= EvidenceRedactor.MAX_CHARS + 1);
        assertEquals(Boolean.TRUE, rows.get(4).get("missing"));
    }

    @Test
    void probeSecretsComeFromSensitiveContextEnvironmentAndConnections() {
        var flow = GenerationFixtures.flow(false);
        var store = GenerationFixtures.store(flow);
        var checks = new StaticChecks(GenerationFixtures.planner(store), store);
        var intent = RuleIntentSource.plan(flow, GenerationFixtures.happy(), GenerationFixtures.operations(), null, Set.of()).intents().getFirst();
        var m = IntentMaterializer.materialize(intent, "h", "happy", "t", 1, null, Map.of(), Set.of());
        var ir = checks.check(GenerationFixtures.confirmed(flow), m.point(), m.issues(), null).ir();
        var profile = new io.yuan.octopus.scenario.testing.TestingModel.ExecutionProfile(Map.of(), Map.of("credentials", Map.of("password", CANARY)), Map.of(), "env");
        var connections = Map.of("c", new io.yuan.octopus.verification.VerificationRuntime.ConnectionSettings("SQL", "h", 1, "d", null, "dbuser", "db-password-1"));
        var secrets = ProbeExecutionService.secrets(ir, profile, connections);
        assertTrue(secrets.containsAll(List.of(CANARY, "db-password-1")));
        assertEquals(Set.of("$.token"), ProbeExecutionService.sensitivePaths(ir));
    }
}
