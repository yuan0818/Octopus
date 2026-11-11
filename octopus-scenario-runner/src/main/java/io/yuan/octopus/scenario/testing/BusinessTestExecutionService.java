package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BusinessTestExecutionService {
    private final BusinessTestScenarioRepository scenarios;
    private final BusinessTestRunRepository runs;
    private final BusinessKnowledgeStore knowledge;
    private final JmxCompiler compiler;
    private final JmeterProcessExecutor executor;
    @org.springframework.beans.factory.annotation.Autowired
    private ProjectContextService contexts;
    @org.springframework.beans.factory.annotation.Autowired
    private EnvironmentBindings environmentBindings;
    @org.springframework.beans.factory.annotation.Autowired
    private RunSubmissionStore submissions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.yuan.octopus.scenario.extension.ExtensionService extensions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PollAuthorizationService pollAuthorizations;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.yuan.octopus.scenario.generation.ProbeEnvironmentService probeEnvironments;
    @Value("${scenario.testing.workspace-id:default}") private String workspaceId="default";
    private final Path home;
    private final Path artifacts;
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
    private final ConcurrentMap<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    public BusinessTestExecutionService(
            BusinessTestScenarioRepository scenarios,
            BusinessTestRunRepository runs,
            BusinessKnowledgeStore knowledge,
            JmxCompiler compiler,
            JmeterProcessExecutor executor,
            @Value("${scenario.jmeter.home:}") String home,
            @Value("${scenario.testing.artifact-dir:data/business-tests}") String artifacts) {
        this.scenarios = scenarios;
        this.runs = runs;
        this.knowledge = knowledge;
        this.compiler = compiler;
        this.executor = executor;
        this.home = home.isBlank() ? null : Path.of(home);
        this.artifacts = Path.of(artifacts).toAbsolutePath();
    }

    public BusinessTestScenario save(TestIR ir) {
        BusinessTestScenario scenario = new BusinessTestScenario();
        scenario.id = UUID.randomUUID().toString();
        scenario.flowId = ir.flowId();
        scenario.flowRevision = ir.flowRevision();
        scenario.testPointId = ir.testPointId();
        scenario.irHash = hash(ir);
        scenario.irJson = canonical(ir);
        scenario.createdAt = Instant.now();
        return scenarios.save(scenario);
    }

    public TestIR load(String id) {
        var scenario =
                scenarios
                        .findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown scenario"));
        TestIR ir = read(scenario.irJson, TestIR.class);
        require(hash(ir).equals(scenario.irHash), "Scenario IR integrity failure");
        var flow = knowledge.flow(ir.flowId(), ir.flowRevision());
        require(
                "CONFIRMED".equals(flow.status()) && ir.flowHash().equals(flow.hash()),
                "Scenario knowledge is not confirmed or has changed");
        var point =
                flow.definition().testPoints().stream()
                        .filter(p -> p.id().equals(ir.testPointId()))
                        .findFirst()
                        .orElseThrow();
        require(hash(point).equals(ir.oracleHash()), "Scenario oracle integrity failure");
        knowledge.validateEvidence(
                ir.flowId(), ir.flowRevision(), ir.testPointId(), ir.changeEvidenceRefs());
        return ir;
    }

    public Compilation compile(String id, ExecutionProfile profile) {
        var ir=load(id);
        return compiler.compile(ir, environmentBindings==null ? profile : environmentBindings.resolve(ir,profile));
    }

    public BusinessTestRun run(String id, ExecutionProfile profile, long timeoutSeconds) {
        return run(id,profile,timeoutSeconds,null);
    }

    public BusinessTestRun run(String id, ExecutionProfile profile, long timeoutSeconds,String idempotencyKey) {
        String requestKey=null,inputHash=null;
        if(idempotencyKey!=null) {
            require(idempotencyKey.matches("[A-Za-z0-9_.:-]{1,128}"),"幂等键需要 1–128 位字母、数字或 _ . : -");
            requestKey=hash(Map.of("workspace",workspaceId,"key",idempotencyKey));
            inputHash=hash(Map.of("scenario",id,"profile",profile,"timeoutSeconds",timeoutSeconds));
            var prior=submissions.find(requestKey,inputHash);if(prior!=null)return prior;
        }
        // ADR-CG-05：开启生成探测的环境只供生成任务使用，业务运行不得进入（隔离、AT-G18）
        require(profile == null || probeEnvironments == null || !probeEnvironments.enabled(profile.environment()),
                "该环境已开启生成探测，仅供生成任务使用；请选择其他测试环境运行已确认的用例");
        TestIR ir = load(id);
        final ExecutionProfile resolvedProfile=environmentBindings==null ? profile : environmentBindings.resolve(ir,profile);
        require(timeoutSeconds >= 1 && timeoutSeconds <= 3600, "Timeout must be 1..3600 seconds");
        executor.verifyRuntime(home);
        Compilation compilation = compiler.compile(ir, resolvedProfile);
        executor.verifyCapabilities(home, compilation);
        // FR-FN-03 / FR-POLL-04: pinned extension versions must be installed; every poll needs a valid authorization.
        if (extensions != null) extensions.verifyRuntime(home, ExtensionUsage.keys(ir));
        if (pollAuthorizations != null) pollAuthorizations.verify(ir, knowledge::operation);
        Map<String, io.yuan.octopus.verification.VerificationRuntime.ConnectionSettings> connections = new TreeMap<>();
        var allCalls=new ArrayList<>(ir.steps());
        if(ir.cleanup()!=null) ir.cleanup().forEach(c->allCalls.add(c.call()));
        for (var step : allCalls) for (var assertion : DatastoreChecks.of(step)) {
            ProjectContextService.environment(profile.environment());
            var check = DatastoreChecks.plan(assertion);
            connections.put(DatastoreChecks.key(check.connection()), contexts.runtimeConnection(check.connection(), profile.environment(), check.kind()));
        }
        require(profile.initialContext() != null, "Initial context required (use {} if empty)");
        ir.initialContext()
                .forEach(
                        (key, type) ->
                                require(
                                        IrValidator.matches(
                                                profile.initialContext().get(key), type.type()),
                                        "Initial context missing or wrong type: " + key));
        BusinessTestRun run = new BusinessTestRun();
        run.id = UUID.randomUUID().toString();
        run.scenarioId = id;
        run.state = "QUEUED";
        run.verdict = "INCONCLUSIVE";
        run.createdAt = Instant.now();
        run.workerId = RunLeaseSupervisor.WORKER_ID;
        run.heartbeatAt = run.createdAt;
        if(requestKey==null) run=runs.save(run);
        else {
            try {run=submissions.reserve(requestKey,inputHash,run);}
            catch(org.springframework.dao.DataIntegrityViolationException conflict) {
                var prior=submissions.find(requestKey,inputHash);if(prior!=null)return prior;throw conflict;
            }
        }
        String runId = run.id;
        AtomicBoolean cancelled = new AtomicBoolean();
        cancellations.put(runId, cancelled);
        try {
            workers.execute(
                    () -> execute(runId, ir, resolvedProfile, compilation, timeoutSeconds, cancelled, connections));
        } catch (RejectedExecutionException e) {
            cancellations.remove(runId);
            run.state = "FAILED";
            run.error = "Execution queue full";
            run.finishedAt = Instant.now();
            runs.save(run);
        }
        return run;
    }

    private void execute(
            String id,
            TestIR ir,
            ExecutionProfile profile,
            Compilation compilation,
            long timeout,
            AtomicBoolean cancelled,
            Map<String, io.yuan.octopus.verification.VerificationRuntime.ConnectionSettings> connections) {
        try {
            var run = runs.findById(id).orElseThrow();
            if(cancelled.get() || "CANCELLED".equals(run.state)) {
                run.state="CANCELLED";run.finishedAt=Instant.now();runs.save(run);return;
            }
            if(run.createdAt.plusSeconds(600).isBefore(Instant.now())) {
                run.state="TIMED_OUT";run.error="Execution queue wait exceeded 600 seconds";run.finishedAt=Instant.now();runs.save(run);return;
            }
            run.state = "RUNNING";
            runs.save(run);
            Path directory = artifacts.resolve(id);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("knowledge-lock.json"), canonical(ir));
            var environmentLock=new LinkedHashMap<String,Object>();
            environmentLock.put("environment",profile.environment());environmentLock.put("services",profile.services());
            environmentLock.put("javaVersion",System.getProperty("java.version"));
            var callLocks=new TreeMap<String,Object>();
            if(profile.callEnvironments()!=null) profile.callEnvironments().forEach((call,snapshot)->callLocks.put(call,Map.of("hash",snapshot.snapshotHash(),"origins",snapshot.origins())));
            environmentLock.put("calls",callLocks);
            Files.writeString(directory.resolve("environment-lock.json"),canonical(environmentLock));
            Files.writeString(
                    directory.resolve("change-evidence.json"),
                    canonical(knowledge.evidenceSnapshots(ir.changeEvidenceRefs())));
            var result =
                    executor.execute(home, directory, compilation, profile, timeout, cancelled, connections);
            run = runs.findById(id).orElseThrow();
            run.state = result.state();
            run.verdict = result.verdict();
            run.reportJson = canonical(result);
            run.error = result.error();
            run.finishedAt = Instant.now();
            runs.save(run);
        } catch (Exception e) {
            var run = runs.findById(id).orElseThrow();
            run.state = "FAILED";
            run.verdict = "INCONCLUSIVE";
            run.error = "Execution infrastructure failure: " + e.getClass().getSimpleName();
            run.finishedAt = Instant.now();
            runs.save(run);
        } finally {
            cancellations.remove(id);
        }
    }

    /** Phase 2.2：本进程正在排队或执行的运行（用于心跳）。 */
    public Set<String> activeRunIds() {
        return Set.copyOf(cancellations.keySet());
    }

    public Path artifactDirectory(String runId) {
        return artifacts.resolve(runId);
    }

    public void cancel(String id) {
        var run = runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown run"));
        AtomicBoolean cancelled = cancellations.get(id);
        if (cancelled != null) {
            cancelled.set(true);
            if("QUEUED".equals(run.state)) {run.state="CANCELLED";run.finishedAt=Instant.now();runs.save(run);}
        }
        else
            require(
                    !Set.of("QUEUED", "RUNNING").contains(run.state),
                    "Run is owned by another or stopped worker; inspect worker before retrying");
    }

    @PreDestroy
    public void close() {
        cancellations.values().forEach(c -> c.set(true));
        workers.shutdown();
    }
}
