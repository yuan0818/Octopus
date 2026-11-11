package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Phase 2.4：数据驱动执行——同一个已确认场景，按数据表逐行运行（每行提供一组初始变量），结果按行汇总。
 * <p>
 * 设计取舍：
 * <ul>
 *   <li>流程用 {@code source=context} 引用初始变量，数据行只替换这些变量的值，因此不需要改 IR、不改变已确认的业务预期；
 *       也不会出现"不同行使用不同 Oracle"这种需要另行确认的情况（那应建成独立测试点）。</li>
 *   <li>数据行会持久化，因此只接受普通测试值：IR 中标记为敏感的变量不能出现在行里（用 secret 引用）。</li>
 *   <li>逐行提交到现有执行队列，全局同时在途数受 {@code max-in-flight} 限制；队列满时保留待执行。</li>
 *   <li>判定：任一行 FAIL → FAIL；否则任一行无法判定 → INCONCLUSIVE；全部通过 → PASS。</li>
 * </ul>
 */
@Service
public class DataRunService {

    private static final Logger log = LoggerFactory.getLogger(DataRunService.class);
    public static final int MAX_ROWS = 200;

    public record RowInput(String name, Map<String, Object> initialContext) {}

    public record StartRequest(String scenarioId, Map<String, Target> services, String environment,
                               Map<String, String> secretEnvironmentNames, Map<String, Object> initialContext,
                               List<RowInput> rows, Long timeoutSeconds) {}

    /** state: PENDING / SUBMITTED / DONE / ERROR。 */
    public record Row(String name, Map<String, Object> initialContext, String runId, String state, String verdict, String reason) {
        Row with(String runId, String state, String verdict, String reason) {
            return new Row(name, initialContext, runId, state, verdict, reason);
        }
    }

    record StoredProfile(Map<String, Target> services, String environment, Map<String, String> secretEnvironmentNames,
                         Map<String, Object> initialContext, long timeoutSeconds) {}

    public record Summary(String id, String scenarioId, String status, String verdict, Map<String, Integer> counts,
                          List<Row> rows, String createdAt, String finishedAt) {}

    private final DataRunRepository dataRuns;
    private final BusinessTestExecutionService execution;
    private final BusinessTestRunRepository runs;
    private final int maxInFlight;
    private ScheduledExecutorService supervisor;

    public DataRunService(DataRunRepository dataRuns, BusinessTestExecutionService execution, BusinessTestRunRepository runs,
                          @Value("${scenario.testing.data-run.max-in-flight:2}") int maxInFlight) {
        this.dataRuns = dataRuns;
        this.execution = execution;
        this.runs = runs;
        this.maxInFlight = Math.max(1, maxInFlight);
    }

    @PostConstruct
    void startSupervisor() {
        supervisor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("data-run-supervisor").daemon(true).factory());
        supervisor.scheduleWithFixedDelay(() -> {
            try { tick(); } catch (Exception e) { log.error("Data run tick failed: {}", e.getMessage(), e); }
        }, 3, 3, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopSupervisor() {
        if (supervisor != null) supervisor.shutdownNow();
    }

    public Summary start(StartRequest request) {
        require(request != null && request.scenarioId() != null, "scenarioId 不能为空");
        TestIR ir = execution.load(request.scenarioId());
        require(request.rows() != null && !request.rows().isEmpty() && request.rows().size() <= MAX_ROWS, "数据行需要 1–" + MAX_ROWS + " 行");
        require(request.services() != null && !request.services().isEmpty(), "需要服务地址");
        request.services().values().forEach(t -> JmxCompiler.targetUri(t.baseUrl()));
        if (request.environment() != null) ProjectContextService.environment(request.environment());
        long timeout = request.timeoutSeconds() == null ? 300 : request.timeoutSeconds();
        require(timeout >= 1 && timeout <= 3600, "timeoutSeconds 需要 1..3600");
        Map<String, Object> base = request.initialContext() == null ? Map.of() : request.initialContext();
        validateValues(ir, base, "基础初始变量");
        Set<String> names = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (RowInput input : request.rows()) {
            require(input != null && input.name() != null && input.name().matches("[\\p{L}\\p{N} _.:#-]{1,80}") && names.add(input.name()),
                    "每行需要唯一的名称（1–80 个字符）");
            Map<String, Object> values = input.initialContext() == null ? Map.of() : input.initialContext();
            validateValues(ir, values, "第 " + input.name() + " 行");
            Map<String, Object> merged = new TreeMap<>(base);
            merged.putAll(values);
            for (var declared : ir.initialContext().entrySet()) {
                if (declared.getValue().sensitive()) continue;  // 敏感变量只能来自 secret / 项目背景，不在此校验
                require(IrValidator.matches(merged.get(declared.getKey()), declared.getValue().type()),
                        "第 " + input.name() + " 行缺少初始变量或类型不符: " + declared.getKey());
            }
            rows.add(new Row(input.name(), new TreeMap<>(values), null, "PENDING", null, null));
        }
        require(ir.initialContext().values().stream().noneMatch(Variable::sensitive),
                "场景声明了敏感初始变量；数据驱动执行不保存敏感值，请改用 secret 引用");
        Map<String, String> secrets = request.secretEnvironmentNames() == null ? Map.of() : request.secretEnvironmentNames();

        DataRun run = new DataRun();
        run.id = UUID.randomUUID().toString();
        run.scenarioId = request.scenarioId();
        run.status = "OPEN";
        run.verdict = "PENDING";
        run.profileJson = canonical(new StoredProfile(new TreeMap<>(request.services()), request.environment(), new TreeMap<>(secrets),
                new TreeMap<>(base), timeout));
        run.rowsJson = canonical(rows);
        run.createdAt = Instant.now();
        run.updatedAt = run.createdAt;
        return summary(dataRuns.save(run));
    }

    public Summary get(String id) {
        return summary(dataRuns.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown data run")));
    }

    public List<Summary> list(String scenarioId) {
        return dataRuns.findTop100ByScenarioIdOrderByCreatedAtDesc(scenarioId).stream().map(this::summary).toList();
    }

    public Summary cancel(String id) {
        DataRun run = dataRuns.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown data run"));
        if (!"OPEN".equals(run.status)) return summary(run);
        List<Row> rows = new ArrayList<>();
        for (Row row : rows(run)) {
            if ("SUBMITTED".equals(row.state())) {
                try { execution.cancel(row.runId()); } catch (RuntimeException ignored) { /* 已结束或不属于本进程 */ }
                rows.add(row.with(row.runId(), "DONE", "INCONCLUSIVE", "已取消"));
            } else if ("PENDING".equals(row.state())) rows.add(row.with(null, "DONE", "INCONCLUSIVE", "已取消"));
            else rows.add(row);
        }
        run.rowsJson = canonical(rows);
        run.status = "CANCELLED";
        run.verdict = aggregate(rows);
        run.finishedAt = Instant.now();
        run.updatedAt = run.finishedAt;
        return summary(dataRuns.save(run));
    }

    void tick() {
        List<DataRun> open = new ArrayList<>(dataRuns.findByStatus("OPEN"));
        open.sort(Comparator.comparing(r -> r.createdAt));
        int inFlight = 0;
        for (DataRun run : open) for (Row row : rows(run)) if ("SUBMITTED".equals(row.state())) inFlight++;
        for (DataRun run : open) {
            try {
                inFlight = advance(run, inFlight);
            } catch (ObjectOptimisticLockingFailureException concurrent) {
                log.debug("Data run {} changed concurrently", run.id);
            }
        }
    }

    int advance(DataRun run, int inFlight) {
        StoredProfile profile = read(run.profileJson, StoredProfile.class);
        List<Row> rows = new ArrayList<>(rows(run));
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (!"SUBMITTED".equals(row.state())) continue;
            var result = runs.findById(row.runId()).orElse(null);
            if (result == null) {
                rows.set(i, row.with(row.runId(), "DONE", "INCONCLUSIVE", "运行记录不存在"));
            } else if (!Set.of("QUEUED", "RUNNING").contains(result.state)) {
                rows.set(i, row.with(row.runId(), "DONE", result.verdict, result.error));
            } else continue;
            inFlight--;
            changed = true;
        }
        for (int i = 0; i < rows.size() && inFlight < maxInFlight; i++) {
            Row row = rows.get(i);
            if (!"PENDING".equals(row.state())) continue;
            Map<String, Object> context = new TreeMap<>(profile.initialContext());
            context.putAll(row.initialContext());
            try {
                var submitted = execution.run(run.scenarioId,
                        new ExecutionProfile(profile.services(), context, profile.secretEnvironmentNames(), profile.environment()),
                        profile.timeoutSeconds(), "data:" + run.id + ":" + i);
                if ("FAILED".equals(submitted.state) && "Execution queue full".equals(submitted.error)) break;
                rows.set(i, row.with(submitted.id, "SUBMITTED", null, null));
                inFlight++;
            } catch (RuntimeException e) {
                String message = String.valueOf(e.getMessage());
                rows.set(i, row.with(null, "ERROR", "INCONCLUSIVE", message.length() > 300 ? message.substring(0, 300) : message));
            }
            changed = true;
        }
        if (rows.stream().noneMatch(r -> "PENDING".equals(r.state()) || "SUBMITTED".equals(r.state()))) {
            run.status = "COMPLETED";
            run.verdict = aggregate(rows);
            run.finishedAt = Instant.now();
            changed = true;
        }
        if (changed) {
            run.rowsJson = canonical(rows);
            run.updatedAt = Instant.now();
            dataRuns.save(run);
        }
        return inFlight;
    }

    /** 数据行只接受已声明、非敏感、类型匹配的初始变量。 */
    private static void validateValues(TestIR ir, Map<String, Object> values, String where) {
        for (var entry : values.entrySet()) {
            Variable declared = ir.initialContext().get(entry.getKey());
            require(declared != null, where + "包含场景未声明的初始变量: " + entry.getKey());
            require(!declared.sensitive(), where + "不能提供敏感变量（数据行会被保存）: " + entry.getKey());
            require(IrValidator.matches(entry.getValue(), declared.type()), where + "变量类型不符: " + entry.getKey());
        }
    }

    static String aggregate(List<Row> rows) {
        boolean fail = false, inconclusive = false;
        for (Row row : rows) {
            if ("FAIL".equals(row.verdict())) fail = true;
            else if (!"PASS".equals(row.verdict())) inconclusive = true;
        }
        return fail ? "FAIL" : inconclusive ? "INCONCLUSIVE" : "PASS";
    }

    Summary summary(DataRun run) {
        List<Row> rows = rows(run);
        Map<String, Integer> counts = new TreeMap<>();
        for (Row row : rows) {
            String key = "DONE".equals(row.state()) || "ERROR".equals(row.state())
                    ? (row.verdict() == null ? "INCONCLUSIVE" : row.verdict()) : row.state();
            counts.merge(key, 1, Integer::sum);
        }
        return new Summary(run.id, run.scenarioId, run.status, run.verdict, counts, rows,
                run.createdAt.toString(), run.finishedAt == null ? null : run.finishedAt.toString());
    }

    private static List<Row> rows(DataRun run) {
        return List.of(read(run.rowsJson, Row[].class));
    }
}
