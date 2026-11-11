package io.yuan.octopus.extension.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.extension.fixture.functions.v1_0_0.EchoJoin;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;

/** Uses the real JMeter 5.6.3 function parser: escaping must preserve every character literally. */
class ExtensionRuntimeTest {
    private static final String CLASS = EchoJoin.class.getName();

    @Test
    void argumentsAreNeverInterpretedAsVariablesOrFunctions() {
        var tricky = List.of("a,1\\b$c", "${__time()}${x}(y)", "3");
        assertEquals("a,1\\b$c|${__time()}${x}(y)|3", ExtensionInvoker.call(CLASS, tricky));
    }

    @Test
    void typedInvocationAppliesOptionalDefaults() {
        var entry = entry();
        assertEquals("x||7", ExtensionInvoker.invoke(entry, Map.of("a", "x")));
        assertEquals("x|y|42", ExtensionInvoker.invoke(entry, Map.of("a", "x", "b", "y", "n", 42L)));
        var missing = assertThrows(ExtensionFailure.class, () -> ExtensionInvoker.invoke(entry, Map.of("b", "y")));
        assertEquals("EXT_ARGUMENT_MISSING", missing.code);
        var unknown = assertThrows(ExtensionFailure.class, () -> ExtensionInvoker.invoke(entry, Map.of("a", "x", "z", "1")));
        assertEquals("EXT_ARGUMENT_UNKNOWN", unknown.code);
        var badType = assertThrows(ExtensionFailure.class, () -> ExtensionInvoker.invoke(entry, Map.of("a", 1L)));
        assertEquals("EXT_ARGUMENT_INVALID", badType.code);
    }

    @Test
    void outputConversionAndErrorCodes() {
        assertEquals(5L, ExtensionInvoker.output("5", "INTEGER"));
        assertEquals(Map.of("a", new java.math.BigDecimal("1")), ExtensionInvoker.output("{\"a\":1}", "OBJECT"));
        assertEquals("EXT_SIGN_BAD", assertThrows(ExtensionFailure.class, () -> ExtensionInvoker.output("\u0000octopus-error:SIGN_BAD", "STRING")).code);
        assertEquals("EXT_OUTPUT_INVALID", assertThrows(ExtensionFailure.class, () -> ExtensionInvoker.output("1.5", "INTEGER")).code);
    }

    @Test
    void probeDiscoversLikeJMeterAndRunsExamples() throws Exception {
        Path jar = Files.createTempFile("fixture", ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            String resource = CLASS.replace('.', '/') + ".class";
            out.putNextEntry(new JarEntry(resource));
            try (InputStream in = EchoJoin.class.getClassLoader().getResourceAsStream(resource)) { in.transferTo(out); }
            out.closeEntry();
        }
        var spec = new ExtensionProbe.Spec();
        spec.jar = jar.toString();
        spec.callTimeoutMillis = 5_000;
        var c = new ExtensionProbe.ClassSpec();
        c.className = CLASS;
        c.referenceKey = "__fixture_echo_v1_0_0";
        c.paramCount = 3;
        c.determinism = "PURE";
        var ok = new ExtensionProbe.Example();
        ok.args = List.of("a,1", "${v}", "3");
        ok.output = "a,1|${v}|3";
        var wrong = new ExtensionProbe.Example();
        wrong.args = List.of("x");
        wrong.output = "nope";
        c.examples = List.of(ok, wrong);
        spec.classes = List.of(c);
        var result = ExtensionProbe.run(spec);
        assertNull(result.error);
        assertTrue(result.discovered.contains(CLASS), "JMeter ClassFinder must discover the function");
        var r = result.classes.get(0);
        assertEquals("__fixture_echo_v1_0_0", r.referenceKey);
        assertTrue(r.examples.get(0).matched);
        assertFalse(r.examples.get(1).matched);
        assertEquals(1, r.problems.size());
        Files.deleteIfExists(jar);
    }

    @Test
    void manifestRoundTripAndRegistry() throws Exception {
        Path dir = Files.createTempDirectory("manifest");
        ExtensionTools.write(dir.resolve(ExtensionTools.MANIFEST), List.of(entry()));
        var read = ExtensionTools.read(dir.resolve(ExtensionTools.MANIFEST));
        assertEquals(1, read.size());
        assertEquals("x.fixture.echo@1.0.0", read.get(0).key());
        ExtensionTools.replaceAll(read);
        assertTrue(ExtensionTools.find("x.fixture.echo", "1.0.0").isPresent());
        assertFalse(ExtensionTools.find("x.fixture.echo", "2.0.0").isPresent());
    }

    @Test
    void whitelistedBuiltinsRunThroughTheSameInvoker() {
        var digest = ExtensionTools.find("jm.digest", "5.6.3").orElseThrow();
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ExtensionInvoker.invoke(digest, Map.of("algorithm", "SHA-256", "text", "abc")));
        var encode = ExtensionTools.find("jm.urlencode", "5.6.3").orElseThrow();
        assertEquals("a+b%2Cc%24", ExtensionInvoker.invoke(encode, Map.of("text", "a b,c$")));
        var upper = ExtensionTools.find("jm.changeCase", "5.6.3").orElseThrow();
        assertEquals("ABC", ExtensionInvoker.invoke(upper, Map.of("text", "abc", "mode", "UPPER")));
        var convert = ExtensionTools.find("jm.dateTimeConvert", "5.6.3").orElseThrow();
        assertEquals("2026-10-14", ExtensionInvoker.invoke(convert, Map.of("date", "14/10/2026", "sourceFormat", "dd/MM/yyyy", "targetFormat", "yyyy-MM-dd")));
        var uuid = ExtensionTools.find("jm.uuid", "5.6.3").orElseThrow();
        assertTrue(String.valueOf(ExtensionInvoker.invoke(uuid, Map.of())).matches("[0-9a-f-]{36}"));
        assertFalse(ExtensionTools.find("jm.digest", "1.0.0").isPresent());
        for (var e : BuiltinFunctions.all())
            assertTrue(e.params.stream().noneMatch(p -> p.name.toLowerCase(java.util.Locale.ROOT).contains("var")), "varName must never be exposed: " + e.id);
    }

    static ExtensionEntry entry() {
        var e = new ExtensionEntry();
        e.id = "x.fixture.echo";
        e.name = "fixture.echo";
        e.version = "1.0.0";
        e.className = CLASS;
        e.referenceKey = "__fixture_echo_v1_0_0";
        e.outputType = "STRING";
        e.determinism = "PURE";
        e.params = List.of(new ExtensionEntry.Param("a", "STRING", true, false),
                new ExtensionEntry.Param("b", "STRING", false, false), new ExtensionEntry.Param("n", "INTEGER", false, false));
        return e;
    }
}
