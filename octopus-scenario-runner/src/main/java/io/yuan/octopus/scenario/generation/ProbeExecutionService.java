package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.scenario.testing.*;
import io.yuan.octopus.verification.VerificationRuntime;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 生成探测执行（设计 8.3）：复用 JmxCompiler 与 JmeterProcessExecutor，独立 1 槽执行池（ENVG-005、G-Q2），
 * 独立表（OBS-007）。不自动重试、不重放（RUN-009、OPER-009）。
 */
@Service
public class ProbeExecutionService {
    public static final String WORKER_ID = "probe:" + UUID.randomUUID().toString().substring(0, 8);
    public static final String EVIDENCE_FILE = "probe-evidence.jsonl";

    public record Submission(String runId, String taskId, int round, String intentId, String candidateHash, TestIR ir,
                             ExecutionProfile profile, ProbeEnvironmentService.Active environment, long timeoutSeconds) {
        @Override public String toString() { return "Submission[" + runId + "]"; }
    }

    private final ProbeRunRepository runs;
    private final ProbeEnvironmentService environments;
    private final JmxCompiler compiler;
    private final JmeterProcessExecutor executor;
    @Autowired(required = false) private ProjectContextService contexts;
    @Autowired(required = false) private io.yuan.octopus.scenario.extension.ExtensionService extensions;
    @Autowired(required = false) private PollAuthorizationService polls;
    @Autowired(required = false) private BusinessKnowledgeStore knowledge;
    private final Path home;
    private final Path artifacts;
    private final ThreadPoolExecutor workers;
    private final ConcurrentMap<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    @Autowired
    public ProbeExecutionService(ProbeRunRepository runs, ProbeEnvironmentService environments, JmxCompiler compiler, JmeterProcessExecutor executor,
                                 @Value("${scenario.jmeter.home:}") String home,
                                 @Value("${scenario.testing.artifact-dir:data/business-tests}") String artifacts) {
        this.runs = runs;
        this.environments = environments;
        this.compiler = compiler;
        this.executor = executor;
        this.home = home == null || home.isBlank() ? null : Path.of(home);
        this.artifacts = Path.of(artifacts).toAbsolutePath().resolve("probes");
        this.workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().name("probe-runner-", 0).daemon(true).factory());
    }

    /** 幂等提交：同一 idempotencyKey 只创建一次运行；已存在时直接返回（LOOP-005）。 */
    public ProbeRun submit(Submission s) {
        String key = s.taskId() + ":" + s.round() + ":" + s.intentId();
        var existing = runs.findByIdempotencyKey(key);
        if (existing.isPresent()) return existing.get();
        var run = new ProbeRun();
        run.id = s.runId();
        run.idempotencyKey = key;
        run.taskId = s.taskId();
        run.roundNo = s.round();
        run.intentId = s.intentId();
        run.candidateHash = s.candidateHash();
        run.irHash = hash(s.ir());
        run.environment = s.environment().environment();
        run.probeEnvRevision = s.environment().revision();
        run.namespace = "gp-" + s.runId().replace("-", "").substring(0, 12);
        run.state = "QUEUED";
        run.createdAt = Instant.now();
        run.heartbeatAt = run.createdAt;
        run.workerId = WORKER_ID;
        try { run = runs.saveAndFlush(run); }
        catch (org.springframework.dao.DataIntegrityViolationException race) { return runs.findByIdempotencyKey(key).orElseThrow(() -> race); }
        var cancelled = new AtomicBoolean();
        cancellations.put(run.id, cancelled);
        String id = run.id;
        try {
            workers.execute(() -> execute(id, s, cancelled));
        } catch (RejectedExecutionException full) {
            cancellations.remove(id);
            finish(id, "REJECTED", false, null, null, null, "探测队列已满，未启动");
        }
        return runs.findById(id).orElseThrow();
    }

    public Optional<ProbeRun> get(String id) { return runs.findById(id); }

    public void cancel(String id) {
        var flag = cancellations.get(id);
        if (flag != null) flag.set(true);
    }

    public Set<String> activeRunIds() { return Set.copyOf(cancellations.keySet()); }

    public Path directory(String runId) { return artifacts.resolve(runId); }

    private void execute(String id, Submission s, AtomicBoolean cancelled) {
        Path directory = directory(id);
        Path evidenceFile = directory.resolve(EVIDENCE_FILE);
        try {
            if (cancelled.get()) { finish(id, "CANCELLED", false, null, null, null, "任务取消，探测未开始"); return; }
            var current = environments.active(s.environment().environment());
            if (current == null || current.revision() != s.environment().revision()) {
                finish(id, "REJECTED", false, null, null, null, "环境探测授权已变化或撤销，未启动"); return;   // ENVG-004
            }
            require(home != null, "未配置 JMeter 运行环境（scenario.jmeter.home）");
            executor.verifyRuntime(home);
            var namespaced = withNamespace(s.ir(), s.profile(), "gp-" + id.replace("-", "").substring(0, 12));
            var compilation = compiler.compile(s.ir(), namespaced);
            executor.verifyCapabilities(home, compilation);
            if (extensions != null) extensions.verifyRuntime(home, ExtensionUsage.keys(s.ir()));
            if (polls != null && knowledge != null) polls.verify(s.ir(), knowledge::operation);
            Map<String, VerificationRuntime.ConnectionSettings> connections = new TreeMap<>();
            var allCalls = new ArrayList<>(s.ir().steps());
            if (s.ir().cleanup() != null) s.ir().cleanup().forEach(c -> allCalls.add(c.call()));
            for (var step : allCalls) for (var assertion : DatastoreChecks.of(step)) {
                require(contexts != null, "数据侧检查需要项目上下文服务");
                var check = DatastoreChecks.plan(assertion);
                connections.put(DatastoreChecks.key(check.connection()), contexts.runtimeConnection(check.connection(), namespaced.environment(), check.kind()));
            }
            var initial = namespaced.initialContext() == null ? Map.<String, Object>of() : namespaced.initialContext();
            s.ir().initialContext().forEach((key, type) -> require(IrValidator.matches(initial.get(key), type.type()), "探测初始上下文缺失或类型不符：" + key));
            // RUNNING 之后才可能调用被测接口（OPER-009 区分“未发出”和“结果未知”）
            var row = runs.findById(id).orElseThrow();
            if (cancelled.get()) { finish(id, "CANCELLED", false, null, null, null, "任务取消，探测未开始"); return; }
            row.state = "RUNNING";
            row.requestsMayHaveBeenSent = true;
            row.heartbeatAt = Instant.now();
            runs.saveAndFlush(row);
            Files.createDirectories(directory);
            var result = executor.execute(home, directory, compilation, namespaced, s.timeoutSeconds(), cancelled, connections,
                    Map.of("octopus.probeEvidenceFile", evidenceFile.toAbsolutePath().toString()));
            var evidence = readEvidence(evidenceFile);
            var redacted = EvidenceRedactor.redact(evidence, sensitivePaths(s.ir()), secrets(s.ir(), namespaced, connections));
            var report = new LinkedHashMap<String, Object>();
            report.put("result", result);
            report.put("sourceMap", compilation.sourceMap());
            finish(id, result.state(), true, canonical(report), canonical(redacted), result.cleanupStatus(), result.error());
        } catch (IllegalArgumentException | IllegalStateException e) {
            var row = runs.findById(id).orElse(null);
            boolean sent = row != null && row.requestsMayHaveBeenSent;
            finish(id, "FAILED", sent, null, null, null, safe(e));
        } catch (Exception e) {
            var row = runs.findById(id).orElse(null);
            boolean sent = row != null && row.requestsMayHaveBeenSent;
            finish(id, "FAILED", sent, null, null, null, "探测执行基础设施失败：" + e.getClass().getSimpleName());
        } finally {
            try { Files.deleteIfExists(evidenceFile); } catch (Exception ignored) { }
            cancellations.remove(id);
        }
    }

    /** OPER-008：流程初始上下文声明了 run.namespace 时注入本次探测的命名空间。 */
    static ExecutionProfile withNamespace(TestIR ir, ExecutionProfile profile, String namespace) {
        var variable = ir.initialContext().get(DataSlotFiller.NAMESPACE_VARIABLE);
        if (variable == null || !"string".equals(variable.type())) return profile;
        Map<String, Object> context = new TreeMap<>(profile.initialContext() == null ? Map.of() : profile.initialContext());
        context.put(DataSlotFiller.NAMESPACE_VARIABLE, namespace);
        return new ExecutionProfile(profile.services(), context, profile.secretEnvironmentNames(), profile.environment(), profile.callEnvironments());
    }

    static List<Map<String, Object>> readEvidence(Path file) {
        List<Map<String, Object>> lines = new ArrayList<>();
        try {
            if (!Files.exists(file) || Files.size(file) > 20_000_000) return lines;
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked") Map<String, Object> row = GSON.fromJson(line, Map.class);
                    if (row != null) lines.add(row);
                } catch (RuntimeException ignored) { }
                if (lines.size() >= 2000) break;
            }
        } catch (java.io.IOException ignored) { }
        return lines;
    }

    static Set<String> sensitivePaths(TestIR ir) {
        Set<String> paths = new HashSet<>();
        for (var step : ir.steps()) for (var output : step.outputs()) if (output.sensitive()) paths.add(EvidenceRedactor.normalize(output.path()));
        return paths;
    }

    static List<String> secrets(TestIR ir, ExecutionProfile profile, Map<String, VerificationRuntime.ConnectionSettings> connections) {
        List<String> values = new ArrayList<>();
        if (profile.initialContext() != null) ir.initialContext().forEach((key, variable) -> {
            Object value = profile.initialContext().get(key);
            if (variable.sensitive() && value != null) collect(value, values);
        });
        if (profile.secretEnvironmentNames() != null) for (String name : profile.secretEnvironmentNames().values()) {
            String value = System.getenv(name);
            if (value != null) values.add(value);
        }
        for (var c : connections.values()) { if (c.password() != null) values.add(c.password()); if (c.username() != null) values.add(c.username()); }
        if (profile.callEnvironments() != null) profile.callEnvironments().values().forEach(env -> env.inputs().forEach((k, v) -> {
            if (EvidenceRedactor.SENSITIVE_NAME.matcher(k).matches() && v != null) values.add(String.valueOf(v));
        }));
        return values;
    }

    private static void collect(Object value, List<String> out) {
        if (value instanceof Map<?, ?> map) map.values().forEach(v -> collect(v, out));
        else if (value instanceof Collection<?> list) list.forEach(v -> collect(v, out));
        else if (value != null) out.add(String.valueOf(value));
    }

    private void finish(String id, String state, boolean sent, String report, String evidence, String cleanup, String error) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var row = runs.findById(id).orElseThrow();
                if (row.terminal()) return;
                row.state = state;
                row.requestsMayHaveBeenSent = sent;
                row.reportJson = report;
                row.evidenceJson = evidence;
                row.cleanupStatus = cleanup;
                row.error = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
                row.finishedAt = Instant.now();
                runs.saveAndFlush(row);
                return;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException concurrent) {
                // 心跳不递增版本；只有回收器会并发修改，重读后按终态判断
            }
        }
        LoggerFactory.getLogger(ProbeExecutionService.class).warn("Probe run {} could not record final state {}", id, state);
    }

    private static String safe(RuntimeException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.length() > 300 ? m.substring(0, 300) : m;
    }

    @PreDestroy
    public void close() {
        cancellations.values().forEach(c -> c.set(true));
        workers.shutdown();
    }
}
