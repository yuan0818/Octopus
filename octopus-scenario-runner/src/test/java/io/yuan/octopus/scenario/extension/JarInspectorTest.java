package io.yuan.octopus.scenario.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.example.octofixture.functions.v1_0_0.*;
import com.example.octofixture.plain.MisplacedTool;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** FR-EXT-04/21–23: static parsing, JMeter compliance, metadata contract and risk scan, without loading classes. */
class JarInspectorTest {
    static byte[] jar(Class<?>... classes) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new JarOutputStream(bytes)) {
            for (Class<?> c : classes) {
                String resource = c.getName().replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(resource));
                try (InputStream in = c.getClassLoader().getResourceAsStream(resource)) { in.transferTo(out); }
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Set<String> codes(ExtensionModel.Inspection r, String severity) {
        return r.issues.stream().filter(i -> i.severity.equals(severity)).map(i -> i.code).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void wellFormedToolIsParsedCompletely() throws IOException {
        var r = JarInspector.inspect(jar(GoodSign.class));
        assertFalse(r.blocked(), String.valueOf(codes(r, "ERROR")));
        assertEquals(1, r.tools.size());
        var t = r.tools.get(0);
        assertEquals("fixture.sign", t.name);
        assertEquals("__fixture_sign_v1_0_0", t.referenceKey);
        assertEquals("x.fixture.sign@1.0.0", t.key());
        assertEquals("PURE", t.determinism);
        assertEquals(List.of("REQUEST_TRANSFORM"), t.stages);
        assertEquals(2, t.params.size());
        assertTrue(t.params.get(1).sensitive);
        assertEquals("INTEGER", t.output.type);
        assertEquals(2, t.examples.size());
        assertEquals("SIGN_EMPTY", t.errors.get(0).code);
        assertTrue(t.sdkBase);
        assertEquals(64, r.sha256.length());
    }

    @Test
    void forbiddenCallsAreRejectedAndRiskyCallsWarned() throws IOException {
        var r = JarInspector.inspect(jar(Spawner.class));
        assertTrue(r.blocked());
        assertTrue(codes(r, "ERROR").contains("RISK_FORBIDDEN"));
        assertTrue(codes(r, "WARNING").contains("RISK_FILE"));
    }

    @Test
    void metadataContractViolationsAreReported() throws IOException {
        var r = JarInspector.inspect(jar(Sparse.class));
        var errors = codes(r, "ERROR");
        for (String code : List.of("NAME_INVALID", "VERSION_INVALID", "TEXT_MISSING", "EXPECTED_VALUE_NOT_PURE", "PARAM_INDEX",
                "PARAM_BOUND", "EXAMPLES_TOO_FEW"))
            assertTrue(errors.contains(code), "missing " + code + " in " + errors);
    }

    @Test
    void jmeterDiscoveryAndVersionedPackageAreEnforced() throws IOException {
        var r = JarInspector.inspect(jar(MisplacedTool.class));
        var errors = codes(r, "ERROR");
        assertTrue(errors.contains("JMETER_DISCOVERY"));
        assertTrue(errors.contains("PACKAGE_VERSION"));
    }

    @Test
    void reservedPackagesAndEmptyJarsAreBlocked() throws IOException {
        var r = JarInspector.inspect(jar(JarInspectorTest.class));
        var errors = codes(r, "ERROR");
        assertTrue(errors.contains("RESERVED_PACKAGE"));
        assertTrue(errors.contains("NO_TOOLS"));
        assertTrue(JarInspector.inspect(new byte[] {1, 2, 3}).blocked());
    }
}
