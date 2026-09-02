package io.yuan.octopus.service;

import io.yuan.octopus.common.CallType;
import io.yuan.octopus.common.ClassType;
import io.yuan.octopus.domain.CallGraph;
import io.yuan.octopus.domain.GoAnalysisOutput;
import io.yuan.octopus.domain.GoAnalysisOutput.GoCallEdge;
import io.yuan.octopus.domain.GoAnalysisOutput.GoField;
import io.yuan.octopus.domain.GoAnalysisOutput.GoMethodNode;
import io.yuan.octopus.domain.GoAnalysisOutput.GoTypeSpec;
import io.yuan.octopus.domain.ParseResult;
import io.yuan.octopus.domain.ParsedClass;
import io.yuan.octopus.domain.ParsedFile;
import io.yuan.octopus.domain.ParsedMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GoGraphAssemblerTest {

    private GoGraphAssembler assembler;

    // ── Fixture: mirrors a two-package Go module ───────────────────────────

    private static final String SVC_GET_USER =
            "demo.example.com/demo/service.Service.GetUser(context.Context,string):*model.User,error";
    private static final String DB_GET =
            "demo.example.com/demo/store.DbStore.Get(context.Context,string):*model.User,error";
    private static final String HANDLE_REQ =
            "demo.example.com/demo/api.HandleRequest(context.Context,string):error";
    private static final String CLEANUP = "demo.example.com/demo/api.cleanup():";

    private GoAnalysisOutput fixture() {
        GoTypeSpec storeIface = new GoTypeSpec(
                "demo.example.com/demo/store.Store", "Store", "interface",
                "demo.example.com/demo/store", "/ws/store/store.go", 8, 10,
                "", List.of(), List.of());
        GoTypeSpec dbStore = new GoTypeSpec(
                "demo.example.com/demo/store.DbStore", "DbStore", "struct",
                "demo.example.com/demo/store", "/ws/store/store.go", 12, 16,
                "", List.of("demo.example.com/demo/store.Store"),
                List.of(new GoField("table", "string")));
        GoMethodNode svcGetUser = new GoMethodNode(
                "demo.example.com/demo/service.Service", "GetUser", SVC_GET_USER,
                List.of("context.Context", "string"), List.of("*model.User", "error"),
                "", "/ws/service/service.go", "func (s *Service) GetUser...", 20, 24);
        GoMethodNode dbGet = new GoMethodNode(
                "demo.example.com/demo/store.DbStore", "Get", DB_GET,
                List.of("context.Context", "string"), List.of("*model.User", "error"),
                "", "/ws/store/store.go", "func (s *DbStore) Get...", 14, 18);
        GoMethodNode handleReq = new GoMethodNode(
                "demo.example.com/demo/api", "HandleRequest", HANDLE_REQ,
                List.of("context.Context", "string"), List.of("error"),
                "", "/ws/api/api.go", "func HandleRequest...", 10, 20);
        GoMethodNode cleanup = new GoMethodNode(
                "demo.example.com/demo/api", "cleanup", CLEANUP,
                List.of(), List.of(), "", "/ws/api/api.go", "func cleanup() {}", 23, 23);

        List<GoCallEdge> edges = List.of(
                new GoCallEdge(HANDLE_REQ, SVC_GET_USER, "DIRECT", 12),
                new GoCallEdge(SVC_GET_USER, DB_GET, "DIRECT", 21),
                new GoCallEdge(HANDLE_REQ, CLEANUP, "DIRECT", 15),
                // Edge referencing a missing callee must be dropped silently.
                new GoCallEdge(HANDLE_REQ, "demo.example.com/demo/gone.Missing():", "DIRECT", 13));

        List<GoAnalysisOutput.GoHttpRoute> routes = List.of(
                new GoAnalysisOutput.GoHttpRoute("/api/users", "GET", HANDLE_REQ, 9),
                new GoAnalysisOutput.GoHttpRoute("/api/orders", "POST", HANDLE_REQ, 10));

        return new GoAnalysisOutput(
                List.of(storeIface, dbStore),
                List.of(svcGetUser, dbGet, handleReq, cleanup),
                edges,
                routes);
    }

    @BeforeEach
    void setUp() {
        assembler = new GoGraphAssembler();
    }

    // ── Graph assembly ──────────────────────────────────────────────────────

    @Test
    void shouldBuildGraphNodesFromMethods() {
        CallGraph graph = assembler.buildCallGraph(fixture());

        assertEquals(4, graph.nodes().size());
        Optional<io.yuan.octopus.domain.MethodNode> handle =
                graph.nodes().stream().filter(n -> n.signature().equals(HANDLE_REQ)).findFirst();
        assertTrue(handle.isPresent());
        assertEquals("demo.example.com/demo/api", handle.get().qualifiedClassName());
        assertEquals("HandleRequest", handle.get().methodName());
        // Multiple Go return values are joined into one returnType string.
        assertEquals("*model.User, error", graph.nodes().stream()
                .filter(n -> n.signature().equals(SVC_GET_USER)).findFirst().orElseThrow().returnType());
    }

    @Test
    void shouldMapCallTypesAndDropDanglingEdges() {
        CallGraph graph = assembler.buildCallGraph(fixture());

        assertEquals(3, graph.edges().size());
        assertTrue(graph.edges().stream().allMatch(e -> e.callType() == CallType.DIRECT));
        assertTrue(graph.edges().stream().noneMatch(
                e -> e.callee().signature().contains("Missing")));
    }

    // ── Metadata assembly ───────────────────────────────────────────────────

    @Test
    void shouldBuildTypeClassesAndPackagePseudoClass() {
        ParseResult result = assembler.buildParseResult(fixture());

        // 4 files (store.go, service.go, api.go) hold the type classes and the
        // api package pseudo-class.
        assertEquals(3, result.parsedFiles().size());

        ParsedClass dbStore = findClass(result, "demo.example.com/demo/store.DbStore");
        assertEquals(ClassType.CLASS, dbStore.classType());
        assertEquals(1, dbStore.fields().size());
        assertEquals(List.of("demo.example.com/demo/store.Store"), dbStore.interfaces());
        assertEquals(1, dbStore.methods().size());
        assertEquals(DB_GET, dbStore.methods().get(0).signature());

        ParsedClass storeIface = findClass(result, "demo.example.com/demo/store.Store");
        assertEquals(ClassType.INTERFACE, storeIface.classType());

        // Free functions land in a pseudo-class named after the package.
        ParsedClass apiPseudo = findClass(result, "demo.example.com/demo/api");
        assertEquals(ClassType.CLASS, apiPseudo.classType());
        assertEquals("api", apiPseudo.simpleName());
        assertEquals(2, apiPseudo.methods().size());
    }

    @Test
    void shouldCarryLineNumbersAndSource() {
        ParseResult result = assembler.buildParseResult(fixture());

        ParsedClass dbStore = findClass(result, "demo.example.com/demo/store.DbStore");
        ParsedMethod get = dbStore.methods().get(0);
        assertEquals(14, get.lineStart());
        assertEquals(18, get.lineEnd());
        assertEquals("func (s *DbStore) Get...", get.sourceCode());
    }

    @Test
    void shouldGroupFilesBySourcePath() {
        ParseResult result = assembler.buildParseResult(fixture());

        ParsedFile storeFile = result.parsedFiles().stream()
                .filter(f -> f.filePath().equals("/ws/store/store.go")).findFirst().orElseThrow();
        // store.go holds both Store and DbStore.
        assertEquals(2, storeFile.classes().size());
        // ParsedFile.packageName derives from the class qname's package part.
        assertEquals("demo.example.com/demo/store", storeFile.packageName());
    }

    @Test
    void shouldAttachHttpRoutesAsSyntheticAnnotations() {
        ParseResult result = assembler.buildParseResult(fixture());

        ParsedClass apiPseudo = findClass(result, "demo.example.com/demo/api");
        assertEquals(2, apiPseudo.methods().size());

        ParsedMethod handler = apiPseudo.methods().stream()
                .filter(m -> m.signature().equals(HANDLE_REQ)).findFirst().orElseThrow();
        assertEquals(2, handler.annotations().size());
        assertEquals("HttpRoute", handler.annotations().get(0).name());
        assertEquals("/api/users", handler.annotations().get(0).parameters().get("path"));
        assertEquals("GET", handler.annotations().get(0).parameters().get("method"));
        assertEquals("/api/orders", handler.annotations().get(1).parameters().get("path"));
    }

    private ParsedClass findClass(ParseResult result, String qName) {
        return result.parsedFiles().stream()
                .flatMap(f -> f.classes().stream())
                .filter(c -> c.qualifiedName().equals(qName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("class not found: " + qName));
    }
}
