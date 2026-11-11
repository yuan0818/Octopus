package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.jmeter.save.SaveService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JmxCompilerTest {
    @TempDir Path temporary;

    @Test
    void serializesReloadableJmxDeterministicallyWithoutEmbeddingRuntimeValues() throws Exception {
        var compiler =
                new JmxCompiler(
                        new IrValidator(new CapabilityRegistry()), new CapabilityRegistry());
        var profile =
                new ExecutionProfile(
                        Map.of("account.auth", new Target("http://127.0.0.1:8912/gateway")),
                        Map.of("credentials", Map.of("password", "do-not-embed")),
                        Map.of());
        var result = compiler.compile(TestingFixtures.ir(), profile);
        assertFalse(result.jmx().contains("do-not-embed"));
        assertEquals(result.jmx(), compiler.compile(TestingFixtures.ir(), profile).jmx());
        Path jmx = temporary.resolve("plan.jmx");
        Files.writeString(jmx, result.jmx());
        assertFalse(SaveService.loadTree(jmx.toFile()).isEmpty());
        assertTrue(result.jmx().indexOf("guard:login") < result.jmx().indexOf("guard:verify"));
        assertTrue(result.sourceMap().containsKey("step:verify"));
    }

    @Test
    void rejectsAbsentOrAmbiguousTarget() {
        var compiler =
                new JmxCompiler(
                        new IrValidator(new CapabilityRegistry()), new CapabilityRegistry());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        compiler.compile(
                                TestingFixtures.ir(),
                                new ExecutionProfile(Map.of(), Map.of(), Map.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> JmxCompiler.targetUri("https://user:password@example.com"));
    }
}
