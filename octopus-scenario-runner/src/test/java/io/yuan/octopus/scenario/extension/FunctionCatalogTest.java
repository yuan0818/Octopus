package io.yuan.octopus.scenario.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.example.octofixture.functions.v1_0_0.GoodSign;
import java.util.*;
import org.junit.jupiter.api.Test;

/** FR-FN-02/20, FR-EXT-13/14: one metadata source → search results, JSON Schema tool definition and documentation. */
class FunctionCatalogTest {
    private static FunctionCatalog.Entry extension() throws Exception {
        var tool = JarInspector.inspect(JarInspectorTest.jar(GoodSign.class)).tools.get(0);
        return FunctionCatalog.fromTool(tool, "", List.of("风险示例"));
    }

    private static FunctionCatalog.Entry plain(String id, String summary, String determinism, String... stages) {
        var e = new FunctionCatalog.Entry();
        e.id = id;
        e.version = "1";
        e.kind = "PLATFORM";
        e.title = id;
        e.summary = summary;
        e.determinism = determinism;
        e.stages = List.of(stages);
        return e;
    }

    @Test
    void searchRanksByRelevanceAndFiltersByStage() throws Exception {
        var catalog = new FunctionCatalog(List.of(extension(), plain("time.now", "生成时间戳", "CLOCK", "REQUEST_VALUE"),
                plain("digest", "计算摘要", "PURE", "REQUEST_VALUE", "EXPECTED_VALUE")));
        var hits = catalog.search("签名", null, 10);
        assertEquals("x.fixture.sign@1.0.0", hits.get(0).name);
        assertTrue(catalog.search("时间戳", "EXPECTED_VALUE", 10).isEmpty());
        assertEquals(List.of("digest@1"), catalog.search("摘要", "EXPECTED_VALUE", 10).stream().map(b -> b.name).collect(java.util.stream.Collectors.toList()));
        assertEquals(3, catalog.search("", null, 10).size());
        assertTrue(catalog.find("x.fixture.sign@1.0.0").isPresent());
    }

    @Test
    void toolDefinitionIsJsonSchemaFromTheSameMetadata() throws Exception {
        var e = extension();
        var schema = FunctionCatalog.argumentSchema(e);
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("body", "secret"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
        @SuppressWarnings("unchecked") var props = (Map<String, Map<String, Object>>) schema.get("properties");
        assertEquals(true, props.get("secret").get("x-sensitive"));
        var described = FunctionCatalog.describe(e);
        assertEquals("x.fixture.sign@1.0.0", described.get("name"));
        assertTrue(described.containsKey("examples"));
        assertTrue(String.valueOf(described.get("usage")).contains("可用于预期值"));
        var md = FunctionCatalog.markdown(e);
        assertTrue(md.contains("`x.fixture.sign@1.0.0`"));
        assertTrue(md.contains("| 1 | `secret` | STRING | 是 | 密钥 | 敏感 |"));
        assertTrue(md.contains("SIGN_EMPTY"));
        assertTrue(md.contains("风险示例"));
    }

    @Test
    void metaToolsDescribeDiscovery() {
        var names = FunctionCatalog.metaTools().stream().map(t -> t.get("name")).collect(java.util.stream.Collectors.toList());
        assertEquals(List.of("search_functions", "describe_function", "try_function"), names);
        assertEquals(List.of("签名", "时间", "间戳"), FunctionCatalog.terms("签名 时间戳"));
    }
}
