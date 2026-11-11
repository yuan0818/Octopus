package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.yuan.octopus.scenario.testing.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 持久化与授权：加密快照、请求幂等、单活动任务、探测幂等、租约回收、环境授权（AT-G02、AT-G14、AT-G18）。 */
class CaseGenerationPersistenceTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CaseGenerationTaskEntity.class, BusinessTestRun.class})
    @EnableJpaRepositories(basePackageClasses = {CaseGenerationTaskRepository.class, BusinessTestRunRepository.class})
    @Import({CaseGenerationStore.class, ContextCredentialCipher.class, ProbeEnvironmentService.class})
    static class Config {}

    ConfigurableApplicationContext open(String name, String admins) {
        return new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.sql.init.mode=never",
                "--scenario.testing.encryption-key=0123456789abcdef0123456789abcdef",
                "--scenario.testing.probe-admins=" + admins,
                "--logging.level.root=ERROR", "--spring.main.banner-mode=off");
    }

    CaseGenerationEngine.Task task(String requestId) {
        var t = new CaseGenerationEngine.Task();
        t.id = UUID.randomUUID().toString();
        t.request = new TaskRequest(requestId, "orders", 1, "happy", List.of(Strategy.RULE), List.of(), 10, null, null, null,
                new ProbeConfig(ProbeMode.ON, "gen-sandbox", Map.of("credentials", Map.of("password", "canary-snapshot-secret")), null, null));
        t.requestHash = TestingJson.hash(t.request);
        t.createdAt = Instant.now().toString();
        return t;
    }

    @Test
    void snapshotsAreEncryptedIdempotentAndOneActiveTaskPerFlow() {
        try (var app = open("casegen_" + UUID.randomUUID(), "")) {
            var store = app.getBean(CaseGenerationStore.class);
            var created = store.create(task("request_aaaaaaaaaaaaaaaa"));
            var row = app.getBean(CaseGenerationTaskRepository.class).findById(created.id).orElseThrow();
            assertFalse(row.snapshotEncrypted.contains("canary-snapshot-secret"));
            assertEquals(created.id, store.create(task("request_aaaaaaaaaaaaaaaa")).id, "same request id returns the same task");
            assertThrows(IllegalArgumentException.class, () -> store.create(task("request_bbbbbbbbbbbbbbbb")), "a second active task for the same flow");
            store.update(created.id, t -> { t.state = TaskState.READY; t.event("READY", "done"); });
            assertNotNull(store.create(task("request_bbbbbbbbbbbbbbbb")), "terminal tasks release the flow");
            assertEquals("canary-snapshot-secret", ((Map<?, ?>) store.get(created.id).request.probe().initialContext().get("credentials")).get("password"));
        }
    }

    @Test
    void probeSubmissionIsIdempotentAndOrphansAreRecoveredWithoutReplay() throws Exception {
        try (var app = open("probe_" + UUID.randomUUID(), "")) {
            var runs = app.getBean(ProbeRunRepository.class);
            var environments = mock(ProbeEnvironmentService.class);
            var registry = new CapabilityRegistry();
            var execution = new ProbeExecutionService(runs, environments, new JmxCompiler(new IrValidator(registry), registry), new JmeterProcessExecutor(), "", "build/tmp-probes");
            try {
                var flow = GenerationFixtures.flow(false);
                var store = GenerationFixtures.store(flow);
                var intent = RuleIntentSource.plan(flow, GenerationFixtures.happy(), GenerationFixtures.operations(), null, Set.of()).intents().getFirst();
                var m = IntentMaterializer.materialize(intent, "h", "happy", "t", 1, null, Map.of(), Set.of());
                var ir = new StaticChecks(GenerationFixtures.planner(store), store).check(GenerationFixtures.confirmed(flow), m.point(), m.issues(), null).ir();
                var env = new ProbeEnvironmentService.Active("gen-sandbox", 1, "DISPOSABLE", 24);
                var profile = new TestingModel.ExecutionProfile(Map.of(), Map.of(), Map.of(), "gen-sandbox");
                // 授权已变化：探测不启动，且标记为没有发出请求
                when(environments.active("gen-sandbox")).thenReturn(new ProbeEnvironmentService.Active("gen-sandbox", 2, "DISPOSABLE", 24));
                var first = execution.submit(new ProbeExecutionService.Submission(UUID.randomUUID().toString(), "task", 1, intent.intentId(), "c", ir, profile, env, 60));
                var again = execution.submit(new ProbeExecutionService.Submission(UUID.randomUUID().toString(), "task", 1, intent.intentId(), "c", ir, profile, env, 60));
                assertEquals(first.id, again.id, "same task/round/intent → same run");
                assertEquals(1, runs.count());
                ProbeRun done = null;
                for (int i = 0; i < 200 && (done == null || !done.terminal()); i++) { Thread.sleep(20); done = runs.findById(first.id).orElseThrow(); }
                assertEquals("REJECTED", done.state);
                assertFalse(done.requestsMayHaveBeenSent);
                // AT-G13：探测运行不进入业务运行表
                assertEquals(0, app.getBean(BusinessTestRunRepository.class).count());

                var stale = new ProbeRun();
                stale.id = UUID.randomUUID().toString(); stale.idempotencyKey = "other:1:x"; stale.taskId = "other"; stale.roundNo = 1; stale.intentId = "x";
                stale.candidateHash = "c"; stale.irHash = "i"; stale.environment = "gen-sandbox"; stale.namespace = "gp"; stale.state = "RUNNING";
                stale.requestsMayHaveBeenSent = true; stale.createdAt = Instant.now().minusSeconds(900); stale.heartbeatAt = stale.createdAt;
                runs.save(stale);
                int recovered = new ProbeLeaseSupervisor(runs, execution, 15, 120).recover(Instant.now());
                assertEquals(1, recovered);
                var interrupted = runs.findById(stale.id).orElseThrow();
                assertEquals("INTERRUPTED", interrupted.state);
                assertTrue(interrupted.requestsMayHaveBeenSent, "a started run may have reached the system under test");
            } finally { execution.close(); }
        }
    }

    @Test
    void onlyAdminsCanEnableAndProductionIsNeverAllowed() {
        // AT-G02
        try (var app = open("env_" + UUID.randomUUID(), "alice")) {
            var service = app.getBean(ProbeEnvironmentService.class);
            var request = new ProbeEnvironmentService.Request(true, "DISPOSABLE", 24, true, "隔离沙箱");
            assertThrows(ProbeEnvironmentService.Forbidden.class, () -> service.put("gen-sandbox", request, "bob"));
            assertThrows(IllegalArgumentException.class, () -> service.put("prod", request, "alice"));
            assertThrows(IllegalArgumentException.class, () -> service.put("production-eu", request, "alice"));
            assertThrows(IllegalArgumentException.class, () -> service.put("gen-sandbox", new ProbeEnvironmentService.Request(true, "DISPOSABLE", 24, false, "x"), "alice"));
            assertThrows(IllegalArgumentException.class, () -> service.put("gen-sandbox", new ProbeEnvironmentService.Request(true, "DISPOSABLE", null, true, "x"), "alice"));
            assertNull(service.active("gen-sandbox"));
            var view = service.put("gen-sandbox", request, "alice");
            assertEquals(1, view.revision());
            assertEquals(new ProbeEnvironmentService.Active("gen-sandbox", 1, "DISPOSABLE", 24), service.active("gen-sandbox"));
            service.revoke("gen-sandbox", "演练结束", "alice");
            assertNull(service.active("gen-sandbox"));
            assertEquals(2, service.history("gen-sandbox").size());
            assertEquals("alice", service.history("gen-sandbox").getFirst().changedBy());
        }
        try (var app = open("env_none_" + UUID.randomUUID(), "")) {
            var service = app.getBean(ProbeEnvironmentService.class);
            assertThrows(ProbeEnvironmentService.Forbidden.class, () -> service.put("gen-sandbox",
                    new ProbeEnvironmentService.Request(true, "DISPOSABLE", 24, true, "x"), "anonymous"), "an empty admin list keeps probing off");
        }
    }

    @Test
    void businessRunsAreRejectedOnProbeEnvironments() throws Exception {
        // ADR-CG-05 / AT-G18
        var execution = new BusinessTestExecutionService(mock(BusinessTestScenarioRepository.class), mock(BusinessTestRunRepository.class),
                mock(BusinessKnowledgeStore.class), mock(JmxCompiler.class), mock(JmeterProcessExecutor.class), "", "build/tmp-runs");
        var environments = mock(ProbeEnvironmentService.class);
        when(environments.enabled("gen-sandbox")).thenReturn(true);
        var field = BusinessTestExecutionService.class.getDeclaredField("probeEnvironments");
        field.setAccessible(true);
        field.set(execution, environments);
        var error = assertThrows(IllegalArgumentException.class, () -> execution.run("scenario", new TestingModel.ExecutionProfile(Map.of(), Map.of(), Map.of(), "gen-sandbox"), 60));
        assertTrue(error.getMessage().contains("生成探测"));
        execution.close();
    }
}
