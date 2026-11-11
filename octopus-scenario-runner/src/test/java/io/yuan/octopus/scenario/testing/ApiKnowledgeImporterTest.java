package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class ApiKnowledgeImporterTest {
    private final ApiKnowledgeImporter importer =
            new ApiKnowledgeImporter(new IrValidator(new CapabilityRegistry()));

    @Test
    void importsTypedPortsAuthenticationAndLocalSchemaRefs() {
        String json =
                """
                {"openapi":"3.0.3","paths":{"/orders/{id}":{"get":{"operationId":"getOrder",
                "parameters":[{"in":"path","name":"id","required":true,"schema":{"type":"string"}}],
                "security":[{"bearer":[]}],"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Order"}}}}}}}},
                "components":{"securitySchemes":{"bearer":{"type":"http","scheme":"bearer"}},"schemas":{"Order":{"type":"object","properties":{"state":{"type":"string","enum":["PAID"]}}}}}}
                """;
        Map<String, Object> document =
                TestingJson.GSON.fromJson(
                        json,
                        new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
        var operations =
                importer.openApi(
                        new ApiKnowledgeImporter.ImportRequest(
                                "commerce", "orders", document, Map.of()));
        assertEquals(1, operations.size());
        var operation = operations.getFirst();
        assertEquals("Bearer ", operation.inputs().get("auth.bearer").prefix());
        assertTrue(operation.inputs().get("path.id").required());
        assertTrue(TestingJson.canonical(operation.responseSchema()).contains("PAID"));
        assertEquals(
                operation.revision(),
                importer.openApi(
                                new ApiKnowledgeImporter.ImportRequest(
                                        "commerce", "orders", document, Map.of()))
                        .getFirst()
                        .revision());
    }

    @Test
    void rejectsRemoteReferenceInsteadOfFetchingIt() {
        Map<String, Object> document =
                Map.of(
                        "openapi",
                        "3.0.3",
                        "paths",
                        Map.of("/x", Map.of("$ref", "https://example.invalid/api")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        importer.openApi(
                                new ApiKnowledgeImporter.ImportRequest(
                                        "s", "svc", document, Map.of())));
    }
}
