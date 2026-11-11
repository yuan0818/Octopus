package io.yuan.octopus.scenario.dsl;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import io.yuan.octopus.scenario.extension.FunctionCatalog;
import io.yuan.octopus.scenario.extension.FunctionCatalogService;
import io.yuan.octopus.scenario.testing.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * FR-API-01–03: lint, translate (optionally saving drafts) and render. API paths resolve against the analysed
 * code catalog at one pinned branch/commit; functions against the unified catalog; polls against explicit
 * per-API authorizations. No LLM is called in this increment.
 */
@Service
public class DslService {
    /** Version context for path resolution: the analysed projects and the exact commit (FR-LANG-11). */
    public record Scope(List<Long> projectIds, String branch, String commitId, String system, String service) {}

    public record TranslateRequest(String source, Scope scope, boolean save) {}

    public record Saved(String scenarioId, String flowId, int revision, String hash, boolean unchanged) {}

    public record Translation(String sourceHash, List<DslTranslator.ScenarioResult> scenarios, List<DslModel.Diagnostic> diagnostics,
                              List<Saved> saved) {}

    public record Lint(List<DslModel.Diagnostic> diagnostics, List<ScenarioSummary> scenarios) {}

    public record ScenarioSummary(String id, String name, int steps, List<String> roles) {}

    private final RepositoryCatalogClient catalog;
    private final BusinessKnowledgeStore store;
    private final FlowContractService contracts;
    private final ScenarioPlanner planner;
    private final FunctionCatalogService functions;
    private final PollAuthorizationService polls;
    private final DslSourceRepository sources;

    public DslService(RepositoryCatalogClient catalog, BusinessKnowledgeStore store, FlowContractService contracts, ScenarioPlanner planner,
                      FunctionCatalogService functions, PollAuthorizationService polls, DslSourceRepository sources) {
        this.catalog = catalog;
        this.store = store;
        this.contracts = contracts;
        this.planner = planner;
        this.functions = functions;
        this.polls = polls;
        this.sources = sources;
    }

    public Lint lint(String source) {
        var doc = DslParser.parse(source);
        var summaries = new ArrayList<ScenarioSummary>();
        for (var s : doc.scenarios) {
            var steps = new ArrayList<>(doc.background);
            steps.addAll(s.steps);
            summaries.add(new ScenarioSummary(DslTranslator.scenarioId(s), s.name, steps.size(), DslChecker.roles(steps, null)));
        }
        return new Lint(doc.diagnostics, summaries);
    }

    public Translation translate(TranslateRequest request) {
        require(request != null && request.scope() != null, "请选择要解析 API 的项目与版本");
        var scope = request.scope();
        require(scope.branch() != null && !scope.branch().isBlank() && scope.commitId() != null && scope.commitId().matches("[a-fA-F0-9]{40}"),
                "需要固定的分支与完整提交，API 版本在转换时固定");
        var translator = new DslTranslator(new CatalogResolver(scope), this::function, op -> polls.forOperation(op).map(PollAuthorizationService::grant));
        var result = translator.translate(request.source(), "dsl");
        var saved = new ArrayList<Saved>();
        if (request.save()) {
            for (var s : result.scenarios) {
                if (!s.translated()) continue;
                saved.add(save(s, result.sourceHash, request.source()));
            }
        }
        return new Translation(result.sourceHash, result.scenarios, result.diagnostics, saved);
    }

    /**
     * Saves a translated scenario as a draft revision; never confirms (FR-CONV-01). When only comments or
     * formatting changed, the flow definition is identical and no new revision is created (FR-CONV-03).
     */
    private Saved save(DslTranslator.ScenarioResult s, String sourceHash, String source) {
        var latest = store.flows().stream().filter(f -> f.definition().flowId().equals(s.flow.flowId()))
                .max(Comparator.comparingInt(f -> f.definition().revision())).orElse(null);
        int base = latest == null ? 0 : latest.definition().revision();
        var candidate = withRevision(s.flow, base);
        if (latest != null && TestingJson.hash(candidate).equals(TestingJson.hash(latest.definition())))
            return new Saved(s.scenarioId, s.flow.flowId(), base, latest.hash(), true);
        planner.validateDraft(candidate);
        var revision = store.saveDraft(candidate);
        var record = new DslSourceRecord();
        record.id = revision.definition().flowId() + "@" + revision.definition().revision();
        record.flowId = revision.definition().flowId();
        record.revision = revision.definition().revision();
        record.scenarioId = s.scenarioId;
        record.sourceHash = sourceHash;
        record.flowHash = revision.hash();
        record.source = source;
        record.createdAt = Instant.now();
        sources.save(record);
        return new Saved(s.scenarioId, record.flowId, record.revision, record.flowHash, false);
    }

    private static Flow withRevision(Flow f, int revision) {
        return new Flow(f.flowId(), revision, f.name(), f.initialContext(), f.nodes(), f.dependencies(), f.stateModels(), f.testPoints(), f.steps(), f.cleanup());
    }

    /** FR-RT-01: any ordered flow (canvas, LLM or DSL) as canonical DSL. */
    public FlowDslRenderer.Rendered render(String flowId, int revision) {
        var flow = store.flow(flowId, revision).definition();
        return FlowDslRenderer.render(flow, store::operation, ref -> polls.forOperation(store.operation(ref)).map(PollAuthorizationService::grant));
    }

    public Optional<DslSourceRecord> source(String flowId, int revision) {
        return sources.findById(flowId + "@" + revision);
    }

    /** Catalog lookup: explicit id (and version), else platform tool, {@code jm.} built-in or the latest published extension. */
    Optional<DslTranslator.FunctionRef> function(String name, String version) {
        var catalog = functions.catalog();
        Optional<FunctionCatalog.Entry> entry;
        if (version != null) entry = catalog.find(name, version).or(() -> catalog.find("x." + name, version));
        else {
            var candidates = catalog.entries().stream().filter(e -> e.id.equals(name) || e.id.equals("x." + name)).toList();
            entry = candidates.stream().max(Comparator.comparing(e -> semver(e.version)));
        }
        return entry.map(e -> {
            var params = e.params.stream().sorted(Comparator.comparingInt(p -> p.index)).toList();
            var output = e.outputs.keySet().iterator().next();
            return new DslTranslator.FunctionRef(e.id, e.version, params.stream().map(p -> p.name).toList(),
                    params.stream().map(p -> p.type.toLowerCase(Locale.ROOT)).toList(), output,
                    e.outputs.get(output).type.toLowerCase(Locale.ROOT), e.stages);
        });
    }

    private static String semver(String v) {
        var parts = v.split("\\.");
        var sb = new StringBuilder();
        for (String p : parts) sb.append(String.format("%06d", p.matches("[0-9]{1,6}") ? Integer.parseInt(p) : 0));
        return sb.toString();
    }

    /** Resolves paths to analysed endpoints at the pinned commit and materializes their code contracts. */
    private final class CatalogResolver implements DslTranslator.ApiResolver {
        private final Scope scope;
        private List<RepositoryCatalogClient.Endpoint> endpoints;
        private final Map<String, DslTranslator.Resolved> cache = new HashMap<>();

        CatalogResolver(Scope scope) { this.scope = scope; }

        @Override
        public DslTranslator.Resolved resolve(String method, String path, String service) {
            String key = method + " " + path + " " + service;
            if (cache.containsKey(key)) return cache.get(key);
            if (endpoints == null) {
                endpoints = new ArrayList<>();
                for (var e : catalog.endpoints(scope.branch(), scope.commitId())) {
                    if (scope.projectIds() != null && !scope.projectIds().isEmpty() && !scope.projectIds().contains(e.projectId())) continue;
                    if ("*".equals(e.httpMethod()))
                        for (String m : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"))
                            endpoints.add(new RepositoryCatalogClient.Endpoint(e.projectId(), e.branch(), e.commitId(), e.signature(), m, e.path()));
                    else endpoints.add(e);
                }
            }
            var matches = endpoints.stream()
                    .filter(e -> DslTranslator.pathMatches(path, e.path()))
                    .filter(e -> method == null || method.equals(e.httpMethod()))
                    .filter(e -> service == null || e.signature().contains(service) || String.valueOf(e.projectId()).equals(service))
                    .toList();
            if (matches.isEmpty()) {
                var near = endpoints.stream().filter(e -> e.path().split("/").length == path.split("/").length)
                        .map(e -> e.httpMethod() + " " + e.path()).distinct().sorted().limit(5).toList();
                throw new DslTranslator.ResolveException("API_NOT_FOUND", "在所选版本中没有找到 " + (method == null ? "" : method + " ") + path, near);
            }
            var methods = matches.stream().map(RepositoryCatalogClient.Endpoint::httpMethod).distinct().toList();
            if (method == null && methods.size() > 1)
                throw new DslTranslator.ResolveException("METHOD_REQUIRED", path + " 有多个 HTTP 方法，请写明方法", methods);
            if (matches.size() > 1)
                throw new DslTranslator.ResolveException("API_AMBIGUOUS", path + " 在范围内被多个实现暴露，请用 service 指定",
                        matches.stream().map(e -> e.httpMethod() + " " + e.path() + "（项目 " + e.projectId() + "，" + e.signature() + "）").toList());
            var endpoint = matches.get(0);
            var operation = store.saveOperation(CodeOperationFactory.create(endpoint, scope.system() == null ? "dsl" : scope.system(),
                    scope.service() == null ? "p" + endpoint.projectId() : scope.service()));
            var node = new FlowNode("c1", endpoint.httpMethod() + " " + endpoint.path(), operation.ref(), Map.of(), List.of(), List.of(), 0);
            var skeleton = new Flow("dsl.resolve", 0, "resolve", Map.of(), List.of(node), List.of(), List.of(),
                    List.of(new TestPoint("tp", "resolve", List.of("c1"), Map.of(), Map.of(), null)),
                    List.of(new FlowStep("s1", "resolve", "SERIAL", List.of(node))));
            var prepared = contracts.prepare(skeleton);
            var resolved = new DslTranslator.Resolved(prepared.operations().get(0), endpoint.path(), prepared.warnings());
            cache.put(key, resolved);
            return resolved;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
