package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Runs the generated artifact with an unmodified JMeter distribution against a loopback fixture.
 */
@EnabledIfEnvironmentVariable(named = "JMETER_HOME", matches = ".+")
class JmeterCliIntegrationTest {
    @TempDir Path directory;
    HttpServer server;
    AtomicInteger loginCalls = new AtomicInteger(), verifyCalls = new AtomicInteger();
    AtomicReference<String> body = new AtomicReference<>(), authorization = new AtomicReference<>();
    String loginResponse = "{\"token\":\"fixture-token\"}", verifyResponse = "{\"ok\":true}";
    int verifyStatus = 200;
    final CapabilityRegistry registry = new CapabilityRegistry();
    final JmxCompiler compiler = new JmxCompiler(new IrValidator(registry), registry);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/login",
                exchange -> {
                    loginCalls.incrementAndGet();
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    byte[] data = loginResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.createContext(
                "/verify",
                exchange -> {
                    verifyCalls.incrementAndGet();
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] data = verifyResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(verifyStatus, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ExecutionProfile profile(Map<String, Object> initial) {
        return new ExecutionProfile(
                Map.of(
                        "account.auth",
                        new Target("http://127.0.0.1:" + server.getAddress().getPort())),
                initial,
                Map.of());
    }

    JmeterProcessExecutor.Result execute(
            TestIR ir, Map<String, Object> initial, long timeout, AtomicBoolean cancelled)
            throws Exception {
        var profile = profile(initial);
        var compilation = compiler.compile(ir, profile);
        var result =
                new JmeterProcessExecutor()
                        .execute(
                                Path.of(System.getenv("JMETER_HOME")),
                                directory,
                                compilation,
                                profile,
                                timeout,
                                cancelled);
        assertFalse(
                Files.exists(directory.resolve("context.json")),
                "Temporary context must be removed");
        if (!"PASS".equals(result.verdict()))
            System.out.println(
                    TestingJson.canonical(result)
                            + "\n"
                            + Files.readString(directory.resolve("process.log"))
                            + "\n"
                            + Files.readString(directory.resolve("jmeter.log")));
        return result;
    }

    Map<String, Object> initial() {
        return Map.of(
                "credentials",
                Map.of("password", "quote\" slash\\ newline\n ${__groovy(throw new Exception())}"));
    }

    @Test
    void executesInferredOrderAndTypedContextWithoutEvaluatingInputAsCode() throws Exception {
        var result = execute(TestingFixtures.ir(), initial(), 60, new AtomicBoolean());
        assertEquals("PASS", result.verdict());
        assertEquals(1, loginCalls.get());
        assertEquals(1, verifyCalls.get());
        assertEquals("Bearer fixture-token", authorization.get());
        assertEquals(
                TestingJson.GSON.toJsonTree(initial().get("credentials")),
                com.google.gson.JsonParser.parseString(body.get()));
        assertEquals(
                List.of("guard:login", "step:login", "guard:verify", "step:verify"),
                result.samples().stream().map(JmeterProcessExecutor.Sample::label).toList());
    }

    @Test
    void extractionFailureStopsDependentNetworkRequest() throws Exception {
        loginResponse = "{}";
        var result = execute(TestingFixtures.ir(), initial(), 60, new AtomicBoolean());
        assertEquals("FAIL", result.verdict());
        assertEquals(1, loginCalls.get());
        assertEquals(0, verifyCalls.get());
    }
    @Test
    void environmentVariableDefaultsReachTheReferencedRequestHeader() throws Exception {
        var source = TestingFixtures.ir();
        var declarations = new LinkedHashMap<>(source.initialContext());
        declarations.put("tenant", new Variable("string", false));
        var variable = new ProjectContextService.EnvironmentVariable("tenant", "string", "qa-tenant-42");
        var defaults = new ProjectContextExecutionDefaultsTest().controller("https://unused.example.com", "https://unused.example.com", List.of(variable), List.of(variable), declarations)
                .defaults(new ExecutionDefaultsService.DefaultsRequest("scenario", "test"));
        var steps = source.steps().stream().map(s -> s.id().equals("verify") ? new Step(s.id(), s.operationRef(), Map.of("token", new Binding("context", "tenant")), s.outputs(), s.states(), s.assertions(), s.waitMillis(), s.preparation()) : s).toList();
        var ir = new TestIR(source.irVersion(), source.scenarioId(), source.flowId(), source.flowRevision(), source.flowHash(), source.testPointId(), source.oracleHash(), declarations,
                source.operations(), source.stateModels(), steps, source.dependencies(), source.requiresCapabilities(), null, List.of());
        var values = new LinkedHashMap<>(initial()); values.putAll(defaults.initialContext());
        assertEquals("PASS", execute(ir, values, 60, new AtomicBoolean()).verdict());
        assertEquals("Bearer qa-tenant-42", authorization.get());
    }

    @Test
    void invalidInitialContextSendsNoHttpRequest() throws Exception {
        var result =
                execute(
                        TestingFixtures.ir(),
                        Map.of("credentials", "wrong-type"),
                        60,
                        new AtomicBoolean());
        assertEquals("FAIL", result.verdict());
        assertEquals(0, loginCalls.get());
        assertEquals(0, verifyCalls.get());
    }

    @Test
    void httpSuccessWithWrongBusinessResponseFails() throws Exception {
        verifyResponse = "{\"ok\":false}";
        assertEquals(
                "FAIL",
                execute(TestingFixtures.ir(), initial(), 60, new AtomicBoolean()).verdict());
    }

    @Test
    void explicitlyExpectedRejectionPassesBusinessOracle() throws Exception {
        verifyStatus = 401;
        verifyResponse = "{\"error\":\"invalid_token\"}";
        var ir = TestingFixtures.ir();
        var steps =
                ir.steps().stream()
                        .map(
                                s ->
                                        s.id().equals("verify")
                                                ? new Step(
                                                        s.id(),
                                                        s.operationRef(),
                                                        s.inputs(),
                                                        s.outputs(),
                                                        s.states(),
                                                        List.of(
                                                                new Assertion(
                                                                        "rejected",
                                                                        "STATUS_EQUALS",
                                                                        null,
                                                                        401),
                                                                new Assertion(
                                                                        "error",
                                                                        "JSON_EQUALS",
                                                                        "$.error",
                                                                        "invalid_token")),
                                                        0)
                                                : s)
                        .toList();
        assertEquals(
                "PASS",
                execute(TestingFixtures.withSteps(ir, steps), initial(), 60, new AtomicBoolean())
                        .verdict());
    }

    TestIR stateIr() {
        var ir = TestingFixtures.ir();
        var model =
                new StateModel(
                        "accountState",
                        "v1",
                        "account",
                        List.of("PENDING", "VERIFIED"),
                        List.of(new Transition("verify", "PENDING", "VERIFIED")),
                        "HUMAN");
        var context = new HashMap<>(ir.initialContext());
        context.put("account.id", new Variable("string", false));
        var steps =
                ir.steps().stream()
                        .map(
                                s ->
                                        s.id().equals("login")
                                                ? new Step(
                                                        s.id(),
                                                        s.operationRef(),
                                                        s.inputs(),
                                                        List.of(
                                                                new Output(
                                                                        "auth.token",
                                                                        "$.token",
                                                                        "string",
                                                                        true),
                                                                new Output(
                                                                        "observed.id",
                                                                        "$.id",
                                                                        "string",
                                                                        false),
                                                                new Output(
                                                                        "observed.state",
                                                                        "$.state",
                                                                        "string",
                                                                        false)),
                                                        s.states(),
                                                        s.assertions(),
                                                        0)
                                                : new Step(
                                                        s.id(),
                                                        s.operationRef(),
                                                        s.inputs(),
                                                        s.outputs(),
                                                        List.of(
                                                                new StateConstraint(
                                                                        "accountState",
                                                                        "verify",
                                                                        "account.id",
                                                                        "observed.state",
                                                                        "$.state",
                                                                        "observed.id",
                                                                        "$.id")),
                                                        s.assertions(),
                                                        0))
                        .toList();
        return new TestIR(
                ir.irVersion(),
                ir.scenarioId(),
                ir.flowId(),
                ir.flowRevision(),
                ir.flowHash(),
                ir.testPointId(),
                ir.oracleHash(),
                context,
                ir.operations(),
                List.of(model),
                steps,
                ir.dependencies(),
                ir.requiresCapabilities(),
                null,
                List.of());
    }

    Map<String, Object> stateInitial() {
        var values = new HashMap<>(initial());
        values.put("account.id", "account-1");
        return values;
    }

    @Test
    void confirmsObservedStateTransitionForTheSameEntity() throws Exception {
        loginResponse = "{\"token\":\"fixture-token\",\"id\":\"account-1\",\"state\":\"PENDING\"}";
        verifyResponse = "{\"ok\":true,\"id\":\"account-1\",\"state\":\"VERIFIED\"}";
        assertEquals("PASS", execute(stateIr(), stateInitial(), 60, new AtomicBoolean()).verdict());
    }

    @Test
    void rejectsObservationFromAnotherEntityBeforeMutation() throws Exception {
        loginResponse = "{\"token\":\"fixture-token\",\"id\":\"account-2\",\"state\":\"PENDING\"}";
        assertEquals("FAIL", execute(stateIr(), stateInitial(), 60, new AtomicBoolean()).verdict());
        assertEquals(0, verifyCalls.get());
    }

    @Test
    void responseFromAnotherEntityCannotProveTheExpectedTransition() throws Exception {
        loginResponse = "{\"token\":\"fixture-token\",\"id\":\"account-1\",\"state\":\"PENDING\"}";
        verifyResponse = "{\"ok\":true,\"id\":\"account-2\",\"state\":\"VERIFIED\"}";
        assertEquals("FAIL", execute(stateIr(), stateInitial(), 60, new AtomicBoolean()).verdict());
        assertEquals(1, verifyCalls.get());
    }

    @Test
    void timeoutCannotBecomePass() throws Exception {
        var ir = TestingFixtures.ir();
        var steps =
                ir.steps().stream()
                        .map(
                                s ->
                                        new Step(
                                                s.id(),
                                                s.operationRef(),
                                                s.inputs(),
                                                s.outputs(),
                                                s.states(),
                                                s.assertions(),
                                                30_000))
                        .toList();
        var result =
                execute(TestingFixtures.withSteps(ir, steps), initial(), 3, new AtomicBoolean());
        assertEquals("TIMED_OUT", result.state());
        assertEquals("INCONCLUSIVE", result.verdict());
        assertEquals(0, loginCalls.get());
    }

    @Test
    void cancellationCannotBecomePass() throws Exception {
        var ir = TestingFixtures.ir();
        var steps =
                ir.steps().stream()
                        .map(
                                s ->
                                        new Step(
                                                s.id(),
                                                s.operationRef(),
                                                s.inputs(),
                                                s.outputs(),
                                                s.states(),
                                                s.assertions(),
                                                30_000))
                        .toList();
        var cancelled = new AtomicBoolean();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                Thread.sleep(3000);
                                cancelled.set(true);
                            } catch (InterruptedException ignored) {
                            }
                        });
        var result = execute(TestingFixtures.withSteps(ir, steps), initial(), 60, cancelled);
        assertEquals("CANCELLED", result.state());
        assertEquals("INCONCLUSIVE", result.verdict());
        assertEquals(0, loginCalls.get());
    }
}
