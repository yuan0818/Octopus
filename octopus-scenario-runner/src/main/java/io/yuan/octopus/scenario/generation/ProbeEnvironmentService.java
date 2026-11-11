package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.canonical;

import io.yuan.octopus.scenario.testing.ProjectContextService;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生成探测的环境授权（ENVG-001～004、G-Q4）。只有配置名单中的管理员可以开启或修改；名单为空时任何人都不能开启。
 * 生产环境（按名称规则）一律拒绝；开启时必须声明非生产与数据策略。
 */
@Service
public class ProbeEnvironmentService {
    public static final Set<String> POLICIES = Set.of("CLEANUP_REQUIRED", "DISPOSABLE");

    public record Request(boolean enabled, String dataPolicy, Integer resetCycleHours, boolean nonProductionDeclared, String reason) {}
    public record View(String environment, boolean enabled, String dataPolicy, Integer resetCycleHours, boolean nonProductionDeclared,
                       String reason, int revision, String updatedBy, String updatedAt) {}
    public record HistoryView(int revision, Map<String, Object> snapshot, String changedBy, String changedAt) {}
    /** 一次探测所依据的有效授权；revision 用于执行前复核（ENVG-004）。 */
    public record Active(String environment, int revision, String dataPolicy, Integer resetCycleHours) {}

    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) { super(message); }
    }

    private final ProbeEnvironmentRepository repository;
    private final ProbeEnvironmentHistoryRepository history;
    private final Set<String> admins;
    private final Pattern production;
    private final String workspaceId;

    public ProbeEnvironmentService(ProbeEnvironmentRepository repository, ProbeEnvironmentHistoryRepository history,
                                   @Value("${scenario.testing.probe-admins:}") String admins,
                                   @Value("${scenario.testing.production-environment-pattern:(?i)^(prod|production|prd|live)([-_].*)?$}") String production,
                                   @Value("${scenario.testing.workspace-id:default}") String workspaceId) {
        this.repository = repository;
        this.history = history;
        Set<String> names = new TreeSet<>();
        for (String name : admins.split(",")) if (!name.isBlank()) names.add(name.strip());
        this.admins = Set.copyOf(names);
        this.production = Pattern.compile(production);
        this.workspaceId = workspaceId;
    }

    public List<View> list() {
        return repository.findByWorkspaceIdOrderByEnvironment(workspaceId).stream().map(ProbeEnvironmentService::view).toList();
    }

    public boolean admin(String user) { return user != null && admins.contains(user); }

    /** 当前有效授权；未开启或不存在时返回 null。 */
    public Active active(String environment) {
        if (environment == null) return null;
        return repository.findByWorkspaceIdAndEnvironment(workspaceId, environment)
                .filter(e -> e.enabled && !production(e.environment))
                .map(e -> new Active(e.environment, e.revision, e.dataPolicy, e.resetCycleHours)).orElse(null);
    }

    public boolean enabled(String environment) { return active(environment) != null; }

    public boolean production(String environment) { return environment != null && production.matcher(environment).matches(); }

    @Transactional
    public View put(String environment, Request request, String user) {
        ProjectContextService.environment(environment);
        if (!admin(user)) throw new Forbidden("只有该环境的管理员可以修改生成探测授权");
        require(request != null, "请求不能为空");
        require(!production(environment), "生产环境不能开启生成探测");
        require(request.reason() != null && !request.reason().isBlank() && request.reason().length() <= 500, "请填写理由（≤500 字符）");
        if (request.enabled()) {
            require(request.nonProductionDeclared(), "开启前必须声明该环境为非生产的隔离测试环境");
            require(POLICIES.contains(request.dataPolicy()), "请选择数据策略：CLEANUP_REQUIRED 或 DISPOSABLE");
            if ("DISPOSABLE".equals(request.dataPolicy()))
                require(request.resetCycleHours() != null && request.resetCycleHours() >= 1 && request.resetCycleHours() <= 24 * 90, "DISPOSABLE 环境需要声明 1–2160 小时的重置周期");
        }
        var now = Instant.now();
        var row = repository.findByWorkspaceIdAndEnvironment(workspaceId, environment).orElseGet(() -> {
            var created = new ProbeEnvironment();
            created.id = UUID.randomUUID().toString();
            created.workspaceId = workspaceId;
            created.environment = environment;
            created.revision = 0;
            return created;
        });
        row.enabled = request.enabled();
        row.dataPolicy = request.dataPolicy() == null ? (row.dataPolicy == null ? "CLEANUP_REQUIRED" : row.dataPolicy) : request.dataPolicy();
        require(POLICIES.contains(row.dataPolicy), "数据策略无效");
        row.resetCycleHours = "DISPOSABLE".equals(row.dataPolicy) ? request.resetCycleHours() : null;
        row.nonProductionDeclared = request.nonProductionDeclared();
        row.reason = request.reason().strip();
        row.revision++;
        row.updatedBy = user;
        row.updatedAt = now;
        var saved = repository.save(row);
        record(saved, user, now);
        return view(saved);
    }

    @Transactional
    public View revoke(String environment, String reason, String user) {
        var row = repository.findByWorkspaceIdAndEnvironment(workspaceId, environment).orElseThrow(() -> new IllegalArgumentException("该环境没有探测授权"));
        return put(environment, new Request(false, row.dataPolicy, row.resetCycleHours, row.nonProductionDeclared,
                reason == null || reason.isBlank() ? "撤销生成探测授权" : reason), user);
    }

    public List<HistoryView> history(String environment) {
        var row = repository.findByWorkspaceIdAndEnvironment(workspaceId, environment).orElse(null);
        if (row == null) return List.of();
        return history.findTop50ByProbeEnvironmentIdOrderByRevisionDesc(row.id).stream().map(h -> {
            @SuppressWarnings("unchecked") Map<String, Object> snapshot = io.yuan.octopus.scenario.testing.TestingJson.GSON.fromJson(h.snapshotJson, Map.class);
            return new HistoryView(h.revision, snapshot, h.changedBy, h.changedAt.toString());
        }).toList();
    }

    private void record(ProbeEnvironment row, String user, Instant now) {
        var h = new ProbeEnvironmentHistory();
        h.id = UUID.randomUUID().toString();
        h.probeEnvironmentId = row.id;
        h.revision = row.revision;
        h.snapshotJson = canonical(view(row));
        h.changedBy = user;
        h.changedAt = now;
        history.save(h);
    }

    static View view(ProbeEnvironment e) {
        return new View(e.environment, e.enabled, e.dataPolicy, e.resetCycleHours, e.nonProductionDeclared, e.reason, e.revision, e.updatedBy,
                e.updatedAt == null ? null : e.updatedAt.toString());
    }
}
