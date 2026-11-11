package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class ApiDocumentationTest {
    @Test void documentRetainsPinnedProvenanceAndCanBeImportedWithinTheSupportedSubset() {
        var operation=TestingFixtures.login();
        var document=ApiDocumentationService.exportContracts("Auth",List.of(operation));
        assertEquals("3.1.0",document.openApi().get("openapi"));
        assertTrue(TestingJson.canonical(document.openApi()).contains("x-octopus-revision"));
        var imported=new ApiKnowledgeImporter(new IrValidator(new CapabilityRegistry())).openApi(
                new ApiKnowledgeImporter.ImportRequest(operation.system(),operation.service(),document.openApi(),Map.of()));
        assertEquals(operation.path(),imported.getFirst().path());assertEquals(operation.responseSchema(),imported.getFirst().responseSchema());
        assertFalse(TestingJson.canonical(document).contains("baseUrl"));
    }
    @Test void exportCannotSilentlyCollapseTwoRevisionsOfTheSameRoute() {
        assertThrows(IllegalArgumentException.class,()->ApiDocumentationService.exportContracts("Auth",List.of(TestingFixtures.login(),TestingFixtures.login())));
    }
    @Test void exportingAnAuthorizationParameterPreservesItsExplicitPrefix() {
        var op=TestingFixtures.verify();var document=ApiDocumentationService.exportContracts("Auth",List.of(op));
        var imported=new ApiKnowledgeImporter(new IrValidator(new CapabilityRegistry())).openApi(new ApiKnowledgeImporter.ImportRequest(op.system(),op.service(),document.openApi(),Map.of()));
        assertEquals("Bearer ",imported.getFirst().inputs().get("header.Authorization").prefix());
    }
}
