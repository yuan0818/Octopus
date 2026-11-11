package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.yuan.octopus.scenario.testing.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 真实 JMeter 探测：证据采集、脱敏、分类（AT-G03、AT-G12、OPER-007）。 */
@EnabledIfEnvironmentVariable(named = "JMETER_HOME", matches = ".+")
class ProbeJmeterIntegrationTest {
    static final String CANARY = "canary-password-77-ZX";

    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ProbeRun.class)
    @EnableJpaRepositories(basePackageClasses = ProbeRunRepository.class)
    static class Config {}

    HttpServer server;
    ConfigurableApplicationContext app;
    Path artifacts;
    final AtomicReference<String> lastLogin = new AtomicReference<>("");
    final AtomicInteger orders = new AtomicInteger();
    volatile int createStatus = 201;

    @BeforeEach
    void start() throws Exception {
        artifacts = Files.createTempDirectory("probe-artifacts");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", e -> {
            lastLogin.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(e, 200, "{\"token\":\"tok-server-998877\"}");
        });
        server.createContext("/orders", e -> {
            orders.incrementAndGet();
            String query = e.getRequestURI().getQuery() == null ? "" : e.getRequestURI().getQuery();
            if (query.contains("quantity=0")) respond(e, 422, "{\"code\":\"QUANTITY_INVALID\",\"message\":\"bad\"}");
            else respond(e, createStatus, "{\"id\":\"o-1\",\"status\":\"CREATED\",\"note\":" + TestingJson.GSON.toJson("echo " + lastLogin.get()) + "}");
        });
        server.start();
        app = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:mem:probe_it_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.sql.init.mode=never", "--logging.level.root=ERROR", "--spring.main.banner-mode=off");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        app.close();
    }

    static void respond(com.sun.net.httpserver.HttpExchange e, int status, String body) throws java.io.IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", "application/json");
        e.sendResponseHeaders(status, data.length);
        e.getResponseBody().write(data);
        e.close();
    }

    /** 在正常下单测试点上增加对 note 的检查，使回应中出现被回显的 canary。 */
    Flow flow() {
        var base = GenerationFixtures.flow(false);
        var happy = GenerationFixtures.happy();
        var assertions = new LinkedHashMap<>(happy.assertions());
        var create = new ArrayList<>(assertions.get("create"));
        create.add(new Assertion("note", "JSON_EQUALS", "$.note", "fine"));
        assertions.put("create", create);
        var point = new TestPoint(happy.id(), happy.title(), happy.covers(), assertions, happy.inputOverrides(), null);
        return new Flow(base.flowId(), base.revision(), base.name(), base.initialContext(), null, List.of(), List.of(), List.of(point), base.steps(), null);
    }

    record Probe(ProbeRun run, Observation observation, TestIR ir) {}

    Probe probe(TestIntent intent, Flow flow) throws Exception {
        var store = GenerationFixtures.store(flow);
        var checks = new StaticChecks(GenerationFixtures.planner(store), store);
        var m = IntentMaterializer.materialize(intent, "h", "happy", "task", 1, null, Map.of(), Set.of());
        var checked = checks.check(GenerationFixtures.confirmed(flow), m.point(), m.issues(), null);
        assertTrue(checked.result().ok(), String.valueOf(checked.result().errors()));
        var environments = mock(ProbeEnvironmentService.class);
        var env = new ProbeEnvironmentService.Active("gen-sandbox", 1, "DISPOSABLE", 24);
        when(environments.active("gen-sandbox")).thenReturn(env);
        var registry = new CapabilityRegistry();
        var execution = new ProbeExecutionService(app.getBean(ProbeRunRepository.class), environments, new JmxCompiler(new IrValidator(registry), registry),
                new JmeterProcessExecutor(), System.getenv("JMETER_HOME"), artifacts.toString());
        try {
            var profile = new ExecutionProfile(Map.of("shop.api", new Target("http://127.0.0.1:" + server.getAddress().getPort())),
                    Map.of("credentials", Map.of("user", "probe-user", "password", CANARY)), Map.of(), "gen-sandbox");
            var submitted = execution.submit(new ProbeExecutionService.Submission(UUID.randomUUID().toString(), "task", 1, intent.intentId(),
                    TestingJson.hash(m.point()), checked.ir(), profile, env, 60));
            ProbeRun run = submitted;
            for (int i = 0; i < 1200 && !run.terminal(); i++) { Thread.sleep(100); run = app.getBean(ProbeRunRepository.class).findById(submitted.id).orElseThrow(); }
            assertTrue(run.terminal(), "probe did not finish");
            if (!"COMPLETED".equals(run.state)) System.out.println(run.error + "\n" + run.reportJson);
            var dir = execution.directory(run.id);
            assertFalse(Files.exists(dir.resolve(ProbeExecutionService.EVIDENCE_FILE)), "raw evidence is deleted after redaction");
            assertFalse(Files.exists(dir.resolve("context.json")));
            var draft = new CandidateDraft(intent.intentId(), 1, m.point(), m.fingerprint(), checked.result(), true, null, List.of());
            JmeterProcessExecutor.Result result = TestingJson.GSON.fromJson(TestingJson.GSON.fromJson(run.reportJson, com.google.gson.JsonObject.class).get("result"),
                    JmeterProcessExecutor.Result.class);
            @SuppressWarnings("unchecked") List<Map<String, Object>> evidence = TestingJson.GSON.fromJson(run.evidenceJson, List.class);
            var observation = ObservationClassifier.observeProbe(intent, draft, checked.ir(),
                    new ObservationClassifier.ProbeOutcome(run.id, run.state, run.requestsMayHaveBeenSent, result, evidence), 1);
            return new Probe(run, observation, checked.ir());
        } finally { execution.close(); }
    }

    @Test
    void contradictionIsObservedWithRedactedEvidence() throws Exception {
        createStatus = 200;   // 契约与已确认测试点都写 201
        var flow = flow();
        var baseline = RuleIntentSource.plan(flow, flow.testPoints().getFirst(), GenerationFixtures.operations(), null, Set.of()).intents().getFirst();
        var probe = probe(baseline, flow);
        assertEquals("COMPLETED", probe.run().state);
        assertTrue(probe.run().requestsMayHaveBeenSent);
        assertEquals(1, orders.get());
        assertTrue(lastLogin.get().contains(CANARY), "the probe really sent the configured credential");
        assertFalse(probe.run().evidenceJson.contains(CANARY), "AT-G12: canary must not be persisted");
        assertFalse(probe.run().reportJson.contains(CANARY));
        assertFalse(probe.run().evidenceJson.contains("tok-server-998877"));
        assertEquals(Conclusion.CONTRADICTED, probe.observation().conclusion());
        var status = probe.observation().assertions().stream().filter(a -> a.callId().equals("create") && a.assertionId().equals("status")).findFirst().orElseThrow();
        assertEquals(AssertionClass.CONTRADICTED, status.classification());
        @SuppressWarnings("unchecked") var createEvidence = (List<Map<String, Object>>) probe.observation().evidence().stream()
                .filter(e -> "create".equals(e.get("callId"))).findFirst().orElseThrow().get("assertions");
        assertEquals("200", createEvidence.stream().filter(r -> "status".equals(r.get("assertionId"))).findFirst().orElseThrow().get("actual"));
        assertTrue(String.valueOf(createEvidence.stream().filter(r -> "note".equals(r.get("assertionId"))).findFirst().orElseThrow().get("actual")).contains("***"));
        // 输出摘要：非敏感输出保留 SHA-256（用于 OPER-003 泄漏检查），敏感 token 不保留
        assertTrue(probe.run().evidenceJson.contains(IntentMaterializer.LeakCheck.digest("o-1")));
        assertFalse(probe.run().evidenceJson.contains(IntentMaterializer.LeakCheck.digest("tok-server-998877")));
    }

    @Test
    void negativeProbeMatchingTheContractIsConsistent() throws Exception {
        var flow = GenerationFixtures.flow(false);
        var plan = new ModelIntentSource.PlanIntent(null, null, "数量 0", "NEGATIVE", "create", Map.of("quantity", 0), null, null, 422,
                List.of(new ModelIntentSource.ModelAssertion("JSON_EQUALS", "$.code", "QUANTITY_INVALID")), null, "r", List.of(), null);
        var intent = ModelIntentSource.toIntent(plan, flow, GenerationFixtures.happy(), OrderedFlows.prefix(flow, GenerationFixtures.happy()), GenerationFixtures.operations(), null);
        intent = intent.withExpectations(PlanGuard.checkSources(intent, flow, GenerationFixtures.operations(), null), intent.issues());
        var probe = probe(intent.withId(PlanGuard.intentId(intent)), flow);
        assertEquals(Conclusion.CONSISTENT, probe.observation().conclusion(), String.valueOf(probe.observation()));
        assertEquals(1, orders.get());
    }
}
