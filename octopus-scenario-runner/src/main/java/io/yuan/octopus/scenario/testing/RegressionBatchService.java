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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 变更触发的批量回归（Phase 1A）：双版本影响分析 → 受影响的已确认测试点 → 生成固定场景 → 逐个执行 → 汇总判定。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>提交即返回</b>：{@link #start} 只落库，影响分析、场景生成与执行由后台监督线程推进，调用方轮询或接收回调。</li>
 *   <li><b>不挤占执行队列</b>：全局最多 {@code max-in-flight} 个运行同时排队/执行（执行服务本身只有 2 个进程、32 个等待位）。</li>
 *   <li><b>不猜测输入</b>：执行地址与初始变量来自项目背景（{@link ExecutionDefaultsService}）和请求中显式给出的普通值；
 *       推导不出唯一地址、缺少初始变量或需要敏感变量的测试点标记为 SKIPPED 并写明原因，汇总判定为 INCONCLUSIVE，
 *       不会因为"跑不了"而被当作通过。</li>
 *   <li><b>判定沿用单次运行语义</b>：任一 FAIL → FAIL；否则任一 INCONCLUSIVE / SKIPPED / ERROR → INCONCLUSIVE；全部 PASS → PASS；
 *       没有受影响的测试点 → NO_AFFECTED_TESTS。</li>
 *   <li><b>可恢复</b>：状态全部持久化；重启后 OPEN 批次继续推进。运行记录丢失归属（执行服务重启）的条目超时后记为 INCONCLUSIVE。</li>
 * </ul>
 */
@Service
public class RegressionBatchService {

    private static final Logger log = LoggerFactory.getLogger(RegressionBatchService.class);

    public record StartRequest(
            List<Long> projectIds,
            String branch,
            String baseCommitId,
            String targetCommitId,
            String environment,
            Long timeoutSeconds,
            Map<String, Object> initialContext,
            Map<String, String> secretEnvironmentNames,
            String triggerSource,
            String externalRef,
            String idempotencyKey,
            String callbackUrl) {}

    /** 单个受影响测试点的执行条目。state: PENDING / SUBMITTED / DONE / SKIPPED / ERROR。 */
    public record Item(
            String flowId,
            int flowRevision,
            String testPointId,
            List<String> evidenceRefs,
            String scenarioId,
            String runId,
            String state,
            String verdict,
            String reason) {
        Item with(String scenarioId, String runId, String state, String verdict, String reason) {
            return new Item(flowId, flowRevision, testPointId, evidenceRefs, scenarioId, runId, state, verdict, reason);
        }
    }

    public record Counts(int total, int pass, int fail, int inconclusive, int skipped, int error, int pending, int running) {}

    public record Summary(
            String id,
            String status,
            String verdict,
            String triggerSource,
            String externalRef,
            String environment,
            String branch,
            String baseCommitId,
            String targetCommitId,
            Counts counts,
            List<Item> items,
            Map<String, Object> impact,
            String callbackStatus,
            String error,
            String createdAt,
            String finishedAt) {}

    /** 持久化的请求（初始变量只保存非敏感普通值，见类注释）。 */
    record StoredRequest(
            List<Long> projectIds,
            String branch,
            String baseCommitId,
            String targetCommitId,
            String environment,
            long timeoutSeconds,
            Map<String, Object> initialContext,
            Map<String, String> secretEnvironmentNames) {}

    private static final Set<String> TERMINAL_RUN_STATES_EXCLUDED = Set.of("QUEUED", "RUNNING");

    private final RegressionBatchRepository batches;
    private final BusinessTestImpactBridge impact;
    private final ScenarioPlanner planner;
    private final BusinessTestExecutionService execution;
    private final ExecutionDefaultsService defaults;
    private final BusinessTestRunRepository runs;
    private final int maxInFlight;
    private final int maxItems;
    private final List<String> callbackPrefixes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Integer> callbackAttempts = new ConcurrentHashMap<>();
    private ScheduledExecutorService supervisor;
    /** 回调共享 token（与 code-intelligence 的 octopus.change-review.regression.callback-token 一致）。 */
    @Value("${scenario.testing.regression.callback-token:${OCTOPUS_REGRESSION_CALLBACK_TOKEN:}}")
    private String callbackToken = "";

    public RegressionBatchService(RegressionBatchRepository batches,
                                  BusinessTestImpactBridge impact,
                                  ScenarioPlanner planner,
                                  BusinessTestExecutionService execution,
                                  ExecutionDefaultsService defaults,
                                  BusinessTestRunRepository runs,
                                  @Value("${scenario.testing.regression.max-in-flight:2}") int maxInFlight,
                                  @Value("${scenario.testing.regression.max-items:200}") int maxItems,
                                  @Value("${scenario.testing.regression.callback-allowed-prefixes:${scenario.testing.query-url:http://localhost:8080}}") String callbackPrefixes) {
        this.batches = batches;
        this.impact = impact;
        this.planner = planner;
        this.execution = execution;
        this.defaults = defaults;
        this.runs = runs;
        this.maxInFlight = Math.max(1, maxInFlight);
        this.maxItems = Math.max(1, maxItems);
        this.callbackPrefixes = Arrays.stream(callbackPrefixes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @PostConstruct
    void startSupervisor() {
        supervisor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("regression-batch-supervisor").daemon(true).factory());
        supervisor.scheduleWithFixedDelay(this::tickSafely, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopSupervisor() {
        if (supervisor != null) supervisor.shutdownNow();
    }

    // ------------------------------------------------------------------ API

    public Summary start(StartRequest request) {
        require(request != null, "请求不能为空");
        require(request.projectIds() != null && !request.projectIds().isEmpty() && request.projectIds().size() <= 50
                && request.projectIds().stream().allMatch(id -> id != null && id > 0), "projectIds 需要 1–50 个已分析项目");
        require(request.branch() != null && !request.branch().isBlank() && request.branch().length() <= 200, "branch 不能为空");
        require(sha(request.baseCommitId()) && sha(request.targetCommitId()), "baseCommitId / targetCommitId 需要完整 40 位 SHA");
        ProjectContextService.environment(request.environment());
        long timeout = request.timeoutSeconds() == null ? 600 : request.timeoutSeconds();
        require(timeout >= 1 && timeout <= 3600, "timeoutSeconds 需要 1..3600");
        String trigger = request.triggerSource() == null || request.triggerSource().isBlank() ? "MANUAL" : request.triggerSource();
        require(Set.of("MANUAL", "GERRIT", "CI").contains(trigger), "triggerSource 只支持 MANUAL / GERRIT / CI");
        require(request.externalRef() == null || request.externalRef().length() <= 255, "externalRef 过长");
        if (request.idempotencyKey() != null) {
            require(request.idempotencyKey().matches("[A-Za-z0-9_.:~@/-]{1,128}"), "幂等键需要 1–128 位字母、数字或 _ . : ~ @ / -");
            var existing = batches.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) return summary(existing.get());
        }
        if (request.callbackUrl() != null && !request.callbackUrl().isBlank()) {
            require(request.callbackUrl().length() <= 500 && callbackAllowed(request.callbackUrl()),
                    "callbackUrl 不在允许的回调地址前缀内（scenario.testing.regression.callback-allowed-prefixes）");
        }
        Map<String, Object> context = request.initialContext() == null ? Map.of() : request.initialContext();
        context.keySet().forEach(k -> IrValidator.identifier(k, "initial context key"));
        Map<String, String> secrets = request.secretEnvironmentNames() == null ? Map.of() : request.secretEnvironmentNames();
        secrets.forEach((k, v) -> {
            IrValidator.identifier(k, "secret reference");
            require(v != null && v.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"), "secret 环境变量名无效: " + k);
        });

        RegressionBatch batch = new RegressionBatch();
        batch.id = UUID.randomUUID().toString();
        batch.triggerSource = trigger;
        batch.externalRef = request.externalRef();
        batch.idempotencyKey = request.idempotencyKey();
        batch.status = "OPEN";
        batch.verdict = "PENDING";
        batch.requestJson = canonical(new StoredRequest(List.copyOf(request.projectIds()), request.branch(),
                request.baseCommitId().toLowerCase(Locale.ROOT), request.targetCommitId().toLowerCase(Locale.ROOT),
                request.environment(), timeout, new TreeMap<>(context), new TreeMap<>(secrets)));
        batch.itemsJson = "[]";
        batch.callbackUrl = blankToNull(request.callbackUrl());
        batch.callbackStatus = batch.callbackUrl == null ? "NOT_REQUIRED" : "PENDING_RESULT";
        batch.createdAt = Instant.now();
        batch.updatedAt = batch.createdAt;
        try {
            batch = batches.save(batch);
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // 并发提交同一幂等键
            if (request.idempotencyKey() != null) {
                var existing = batches.findByIdempotencyKey(request.idempotencyKey());
                if (existing.isPresent()) return summary(existing.get());
            }
            throw conflict;
        }
        log.info("Regression batch {} accepted (trigger={}, ref={})", batch.id, trigger, batch.externalRef);
        return summary(batch);
    }

    public Summary get(String id) {
        return summary(batches.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown regression batch")));
    }

    public List<Summary> list() {
        return batches.findTop100ByOrderByCreatedAtDesc().stream().map(this::summary).toList();
    }

    public Summary cancel(String id) {
        RegressionBatch batch = batches.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown regression batch"));
        if (!"OPEN".equals(batch.status)) return summary(batch);
        List<Item> items = new ArrayList<>();
        for (Item item : items(batch)) {
            if ("SUBMITTED".equals(item.state()) && item.runId() != null) {
                try { execution.cancel(item.runId()); } catch (RuntimeException e) { log.debug("cancel run {}: {}", item.runId(), e.getMessage()); }
                items.add(item.with(item.scenarioId(), item.runId(), "DONE", "INCONCLUSIVE", "批次已取消"));
            } else if ("PENDING".equals(item.state())) {
                items.add(item.with(item.scenarioId(), null, "SKIPPED", null, "批次已取消"));
            } else items.add(item);
        }
        batch.itemsJson = canonical(items);
        batch.status = "CANCELLED";
        batch.verdict = aggregate(items);
        finish(batch);
        return summary(batches.save(batch));
    }

    // ------------------------------------------------------------------ supervisor

    void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            log.error("Regression batch supervisor tick failed: {}", e.getMessage(), e);
        }
    }

    /** 推进所有 OPEN 批次并投递待发送的回调（单线程调用）。 */
    void tick() {
        List<RegressionBatch> open = batches.findByStatus("OPEN");
        open.sort(Comparator.comparing(b -> b.createdAt));
        int inFlight = 0;
        for (RegressionBatch batch : open) {
            for (Item item : items(batch)) if ("SUBMITTED".equals(item.state())) inFlight++;
        }
        for (RegressionBatch batch : open) {
            try {
                if (batch.impactJson == null) prepare(batch);
                inFlight = advance(batch, inFlight);
            } catch (ObjectOptimisticLockingFailureException concurrent) {
                log.debug("Regression batch {} changed concurrently; retry next tick", batch.id);
            }
        }
        for (RegressionBatch batch : batches.findByCallbackStatus("PENDING")) deliverCallback(batch);
    }

    /** 影响分析 + 为每个受影响测试点生成固定场景。 */
    void prepare(RegressionBatch batch) {
        StoredRequest request = read(batch.requestJson, StoredRequest.class);
        BusinessTestImpactBridge.Impact result;
        try {
            Map<String, Object> impactRequest = new LinkedHashMap<>();
            impactRequest.put("projectIds", request.projectIds());
            impactRequest.put("branch", request.branch());
            impactRequest.put("baseCommitId", request.baseCommitId());
            impactRequest.put("targetCommitId", request.targetCommitId());
            result = impact.analyze(impactRequest);
        } catch (RuntimeException e) {
            batch.status = "FAILED";
            batch.verdict = "INCONCLUSIVE";
            batch.error = bounded("影响分析失败: " + e.getMessage());
            batch.impactJson = canonical(Map.of("status", "FAILED"));
            finish(batch);
            batches.save(batch);
            return;
        }
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var point : result.testPoints()) {
            if (!seen.add(point.flowId() + "@" + point.flowRevision() + "#" + point.testPointId())) continue;
            if (items.size() >= maxItems) break;
            Item item = new Item(point.flowId(), point.flowRevision(), point.testPointId(), point.evidenceRefs(),
                    null, null, "PENDING", null, null);
            try {
                TestIR ir = planner.generate(point.flowId(), point.flowRevision(), point.testPointId(), point.evidenceRefs());
                String scenarioId = execution.save(ir).id;
                String blocker = inputBlocker(ir, scenarioId, request);
                items.add(blocker == null ? item.with(scenarioId, null, "PENDING", null, null)
                        : item.with(scenarioId, null, "SKIPPED", null, blocker));
            } catch (RuntimeException e) {
                items.add(item.with(null, null, "ERROR", null, bounded("场景生成失败: " + e.getMessage())));
            }
        }
        Map<String, Object> impactSummary = new LinkedHashMap<>();
        // code-intelligence 的 business-test-impact 报告：coverage = STATIC_GRAPH | PARTIAL，warnings 为不完整原因
        Object coverage = result.codeImpact() == null ? null : result.codeImpact().get("coverage");
        impactSummary.put("codeImpactStatus", coverage == null ? "UNKNOWN" : String.valueOf(coverage));
        Object reasons = result.codeImpact() == null ? null : result.codeImpact().get("warnings");
        if (reasons != null) impactSummary.put("partialReasons", reasons);
        impactSummary.put("affectedTestPoints", result.testPoints().size());
        impactSummary.put("truncated", result.testPoints().size() > maxItems);
        impactSummary.put("unmappedEndpoints", result.unmappedEndpoints());
        batch.impactJson = canonical(impactSummary);
        batch.itemsJson = canonical(items);
        batch.updatedAt = Instant.now();
        batches.save(batch);
        log.info("Regression batch {} prepared: {} affected test point(s)", batch.id, items.size());
    }

    /** 刷新已提交条目、按全局上限提交待执行条目，全部结束时完成批次。返回更新后的全局在途数。 */
    int advance(RegressionBatch batch, int inFlight) {
        if (!"OPEN".equals(batch.status)) return inFlight;
        StoredRequest request = read(batch.requestJson, StoredRequest.class);
        List<Item> items = new ArrayList<>(items(batch));
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (!"SUBMITTED".equals(item.state())) continue;
            var run = runs.findById(item.runId()).orElse(null);
            Item updated = item;
            if (run == null) {
                updated = item.with(item.scenarioId(), item.runId(), "DONE", "INCONCLUSIVE", "运行记录不存在");
            } else if (!TERMINAL_RUN_STATES_EXCLUDED.contains(run.state)) {
                updated = item.with(item.scenarioId(), item.runId(), "DONE", run.verdict, withFlakyNote(item, run.verdict, run.error));
            } else if (run.createdAt.plusSeconds(request.timeoutSeconds() + 900).isBefore(Instant.now())) {
                // 执行服务重启后遗留的 QUEUED/RUNNING 记录不会再推进（见 BUSINESS_TESTING.md）
                updated = item.with(item.scenarioId(), item.runId(), "DONE", "INCONCLUSIVE", "运行超时未结束（执行进程可能已重启），请检查残留 JMeter 进程");
            }
            if (updated != item) {
                items.set(i, updated);
                inFlight--;
                changed = true;
            }
        }
        for (int i = 0; i < items.size() && inFlight < maxInFlight; i++) {
            Item item = items.get(i);
            if (!"PENDING".equals(item.state())) continue;
            Item updated;
            try {
                var profile = profile(item.scenarioId(), request);
                var run = execution.run(item.scenarioId(), profile, request.timeoutSeconds(), "batch:" + batch.id + ":" + i);
                if ("FAILED".equals(run.state) && "Execution queue full".equals(run.error)) {
                    break;   // 执行队列已满：保留 PENDING，下一轮再试
                }
                updated = item.with(item.scenarioId(), run.id, "SUBMITTED", null, null);
                inFlight++;
            } catch (RuntimeException e) {
                updated = item.with(item.scenarioId(), null, "ERROR", null, bounded("提交运行失败: " + e.getMessage()));
            }
            items.set(i, updated);
            changed = true;
        }
        boolean finished = items.stream().noneMatch(it -> "PENDING".equals(it.state()) || "SUBMITTED".equals(it.state()));
        if (finished) {
            batch.status = "COMPLETED";
            batch.verdict = aggregate(items);
            finish(batch);
            changed = true;
            log.info("Regression batch {} completed: {}", batch.id, batch.verdict);
        }
        if (changed) {
            batch.itemsJson = canonical(items);
            batch.updatedAt = Instant.now();
            batches.save(batch);
        }
        return inFlight;
    }

    // ------------------------------------------------------------------ helpers

    /** Phase 2.5：可选注入；失败结果若近期 PASS/FAIL 交替，在原因中提示（判定不变）。 */
    private RunHistoryService history;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHistory(RunHistoryService history) {
        this.history = history;
    }

    static final String FLAKY_NOTE = "近期结果在通过/失败之间反复（疑似不稳定，也可能是部署版本变化），请结合运行历史确认";

    private String withFlakyNote(Item item, String verdict, String reason) {
        if (history == null || !"FAIL".equals(verdict)) return reason;
        try {
            if (!history.flakiness(item.flowId(), item.flowRevision(), item.testPointId()).flaky()) return reason;
        } catch (RuntimeException e) {
            return reason;
        }
        return bounded(reason == null || reason.isBlank() ? FLAKY_NOTE : reason + "；" + FLAKY_NOTE);
    }

    /** 执行前置条件检查：缺少地址或初始变量时返回原因，否则返回 null。 */
    private String inputBlocker(TestIR ir, String scenarioId, StoredRequest request) {
        List<String> sensitive = new ArrayList<>(), missing = new ArrayList<>();
        var resolved = defaults.resolve(new ExecutionDefaultsService.DefaultsRequest(scenarioId, request.environment()));
        for (var entry : ir.initialContext().entrySet()) {
            boolean fromRequest = request.initialContext().containsKey(entry.getKey());
            // 批次请求会持久化，敏感变量不能经由它提供（只能来自项目背景或 secret 引用）
            if (entry.getValue().sensitive() && fromRequest) { sensitive.add(entry.getKey()); continue; }
            Object value = fromRequest ? request.initialContext().get(entry.getKey()) : resolved.initialContext().get(entry.getKey());
            if (!IrValidator.matches(value, entry.getValue().type())) {
                if (entry.getValue().sensitive()) sensitive.add(entry.getKey()); else missing.add(entry.getKey());
            }
        }
        List<String> reasons = new ArrayList<>(resolved.warnings());
        if (!missing.isEmpty()) reasons.add("缺少初始变量（可在批次请求 initialContext 中提供普通测试值，或在项目背景中配置）: " + String.join(", ", missing));
        if (!sensitive.isEmpty()) reasons.add("需要敏感初始变量，批量回归不保存敏感值，请改用 secret 引用或手动运行: " + String.join(", ", sensitive));
        return reasons.isEmpty() ? null : bounded(String.join("；", reasons));
    }

    private ExecutionProfile profile(String scenarioId, StoredRequest request) {
        var resolved = defaults.resolve(new ExecutionDefaultsService.DefaultsRequest(scenarioId, request.environment()));
        require(resolved.warnings().isEmpty(), "执行地址不唯一或缺失: " + String.join("；", resolved.warnings()));
        Map<String, Object> context = new TreeMap<>(resolved.initialContext());
        context.putAll(request.initialContext());
        return new ExecutionProfile(resolved.services(), context, request.secretEnvironmentNames(), request.environment());
    }

    static String aggregate(List<Item> items) {
        if (items.isEmpty()) return "NO_AFFECTED_TESTS";
        boolean fail = false, inconclusive = false;
        for (Item item : items) {
            if ("DONE".equals(item.state()) && "FAIL".equals(item.verdict())) fail = true;
            else if (!("DONE".equals(item.state()) && "PASS".equals(item.verdict()))) inconclusive = true;
        }
        return fail ? "FAIL" : inconclusive ? "INCONCLUSIVE" : "PASS";
    }

    static Counts counts(List<Item> items) {
        int pass = 0, fail = 0, inconclusive = 0, skipped = 0, error = 0, pending = 0, running = 0;
        for (Item item : items) {
            switch (item.state()) {
                case "PENDING" -> pending++;
                case "SUBMITTED" -> running++;
                case "SKIPPED" -> skipped++;
                case "ERROR" -> error++;
                default -> {
                    if ("PASS".equals(item.verdict())) pass++;
                    else if ("FAIL".equals(item.verdict())) fail++;
                    else inconclusive++;
                }
            }
        }
        return new Counts(items.size(), pass, fail, inconclusive, skipped, error, pending, running);
    }

    private void finish(RegressionBatch batch) {
        batch.finishedAt = Instant.now();
        batch.updatedAt = batch.finishedAt;
        if ("PENDING_RESULT".equals(batch.callbackStatus)) batch.callbackStatus = "PENDING";
    }

    private void deliverCallback(RegressionBatch batch) {
        int attempt = callbackAttempts.merge(batch.id, 1, Integer::sum);
        try {
            var builder = HttpRequest.newBuilder(URI.create(batch.callbackUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");
            if (callbackToken != null && !callbackToken.isBlank()) builder.header("X-Octopus-Callback-Token", callbackToken);
            var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(canonical(summary(batch)))).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                batch.callbackStatus = "DELIVERED";
                callbackAttempts.remove(batch.id);
            } else if (attempt >= 5) {
                batch.callbackStatus = "FAILED";
                log.warn("Regression batch {} callback failed with HTTP {}", batch.id, response.statusCode());
            } else return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            if (attempt < 5) return;
            batch.callbackStatus = "FAILED";
            log.warn("Regression batch {} callback failed: {}", batch.id, e.getClass().getSimpleName());
        }
        batch.updatedAt = Instant.now();
        try { batches.save(batch); } catch (ObjectOptimisticLockingFailureException ignored) { /* 下一轮重试 */ }
    }

    private boolean callbackAllowed(String url) {
        try {
            URI uri = URI.create(url);
            if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)) || uri.getUserInfo() != null) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
        return callbackPrefixes.stream().anyMatch(prefix -> url.startsWith(prefix.endsWith("/") ? prefix : prefix + "/") || url.equals(prefix));
    }

    Summary summary(RegressionBatch batch) {
        StoredRequest request = read(batch.requestJson, StoredRequest.class);
        List<Item> items = items(batch);
        @SuppressWarnings("unchecked")
        Map<String, Object> impactSummary = batch.impactJson == null ? null : GSON.fromJson(batch.impactJson, Map.class);
        return new Summary(batch.id, batch.status, batch.verdict, batch.triggerSource, batch.externalRef,
                request.environment(), request.branch(), request.baseCommitId(), request.targetCommitId(),
                counts(items), items, impactSummary, batch.callbackStatus, batch.error,
                batch.createdAt == null ? null : batch.createdAt.toString(),
                batch.finishedAt == null ? null : batch.finishedAt.toString());
    }

    private static List<Item> items(RegressionBatch batch) {
        return List.of(read(batch.itemsJson, Item[].class));
    }

    private static boolean sha(String value) {
        return value != null && value.matches("[a-fA-F0-9]{40}");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String bounded(String text) {
        if (text == null) return null;
        return text.length() <= 480 ? text : text.substring(0, 480) + "…";
    }
}
