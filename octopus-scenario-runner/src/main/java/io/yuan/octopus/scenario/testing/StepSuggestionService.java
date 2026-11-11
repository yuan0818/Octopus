package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import org.springframework.stereotype.Service;
import java.util.*;

/** Each proposal changes exactly one human-selected node, with bounded format/binding correction. */
@Service
public class StepSuggestionService {
    public record Prepare(Flow flow, String nodeId, String environment) {}
    public record Request(Flow flow, String nodeId, String environment, String goal, String model,
                          Map<String, Object> contextValues, List<String> editableInputs) {}
    public record Prepared(Flow flow, List<Operation> operations, List<String> warnings, String nodeId,
                           List<RepositoryCatalogClient.SourceEvidence> sources, List<Map<String, Object>> background,
                           List<StepMemoryService.View> memories, List<String> upstreamNodeIds) {}
    public record Patch(FlowSuggestionService.NodeFill node, List<FlowSuggestionService.AssertionFill> assertions,
                        StepMemoryService.MemoryDraft memory, List<String> assumptions, List<String> questions) {}
    public record Suggestion(Flow flow, List<Operation> operations, String model, List<String> assumptions,
                             List<String> questions, List<String> warnings, String baseHash, String nodeId, StepMemoryService.View memory) {}
    public record Confirmation(String memoryId, Flow flow, String environment) {}
    private record Background(List<Map<String, Object>> values, String hash, List<RepositoryCatalogClient.SourceEvidence> sources) {}
    static final String COVERAGE_LIMIT = "静态代码与仓库归纳不能替代当前部署的动态配置；需确认入口路由、签名算法配置、运行时密钥来源及签名加密预处理能力。";
    private final FlowContractService contracts;
    private final RepositoryCatalogClient catalog;
    private final ProjectContextService contexts;
    private final BusinessKnowledgeStore knowledge;
    private final FlowModelClient llm;
    private final StepMemoryService memories;
    private final RepositoryAnalysisService repositoryAnalysis;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.yuan.octopus.scenario.extension.FunctionCatalogService functionCatalog;
    @org.springframework.beans.factory.annotation.Autowired
    public StepSuggestionService(FlowContractService contracts, RepositoryCatalogClient catalog, ProjectContextService contexts,
                                 BusinessKnowledgeStore knowledge, FlowModelClient llm, StepMemoryService memories,RepositoryAnalysisService repositoryAnalysis) {
        this.contracts = contracts; this.catalog = catalog; this.contexts = contexts; this.knowledge = knowledge; this.llm = llm; this.memories = memories;this.repositoryAnalysis=repositoryAnalysis;
    }
    StepSuggestionService(FlowContractService contracts, RepositoryCatalogClient catalog, ProjectContextService contexts,BusinessKnowledgeStore knowledge, FlowModelClient llm, StepMemoryService memories) {this(contracts,catalog,contexts,knowledge,llm,memories,null);}
    public Prepared prepare(Prepare request) {
        validate(request.flow(), request.nodeId(), request.environment());
        var prepared = contracts.prepare(request.flow(), request.nodeId());
        var warnings = new ArrayList<>(prepared.warnings());
        var selected = selected(prepared.flow()); var operation = selected.get(node(prepared.flow(), request.nodeId()).operationRef());
        var source = sources(operation, warnings);
        var backgrounds = backgrounds(prepared.flow(), request.environment(), selected);
        return new Prepared(prepared.flow(), prepared.operations(), warnings.stream().distinct().toList(), request.nodeId(), mergeSources(source,backgrounds.get(request.nodeId()).sources()),
                backgrounds.get(request.nodeId()).values(), memories.views(prepared.flow(), request.environment(), hashes(backgrounds)),
                upstream(prepared.flow(), request.nodeId()));
    }
    /**
     * 同步接口（浏览器 270 秒、Nginx 300 秒）：工具发现、格式修正和语义修正的全部模型轮次共用一个总时限，
     * 剩余时间不足时以 MODEL_TIMEOUT 结束，不在调用方放弃等待后才完成。整链补全在后台执行，使用更长的时限。
     */
    @org.springframework.beans.factory.annotation.Value("${scenario.testing.model.interactive-budget-seconds:240}")
    long interactiveBudgetSeconds = 240;
    @org.springframework.beans.factory.annotation.Value("${scenario.testing.model.background-budget-seconds:900}")
    long backgroundBudgetSeconds = 900;
    static final java.time.Duration STEP_CALL = java.time.Duration.ofSeconds(260);
    public Suggestion suggest(Request request) {
        return suggest(request, List.of(), ModelDeadline.after(java.time.Duration.ofSeconds(Math.min(interactiveBudgetSeconds, 260))));
    }
    Suggestion suggest(Request request, List<String> workingMemoryIds) {
        return suggest(request, workingMemoryIds, ModelDeadline.after(java.time.Duration.ofSeconds(backgroundBudgetSeconds)));
    }
    Suggestion suggest(Request request, List<String> workingMemoryIds, ModelDeadline deadline) {
        validate(request.flow(), request.nodeId(), request.environment());
        require(request.goal() != null && !request.goal().isBlank() && request.goal().length() <= 12000, "请填写当前步骤的补全目标");
        require(request.editableInputs() != null, "请选择可补全参数");
        var validation = new FlowSuggestionService.Request(request.flow(), request.environment(), request.goal(), request.model(),
                request.contextValues(), Map.of(request.nodeId(), request.editableInputs()));
        FlowFillValidator.validateRequest(validation);
        var flow = request.flow(); var focus = node(flow, request.nodeId()); var selected = selected(flow); var operation = selected.get(focus.operationRef());
        require(operation.inputs().keySet().containsAll(request.editableInputs()), "当前步骤包含不存在的输入参数");
        var warnings = new ArrayList<String>(); var source = sources(operation, warnings);
        var backgrounds = backgrounds(flow, request.environment(), selected); var contextHashes = hashes(backgrounds);
        source=mergeSources(source,backgrounds.get(focus.id()).sources());
        var upstream = upstream(flow, focus.id());
        var memoryViews = memories.views(flow, request.environment(), contextHashes);
        var working = memories.working(workingMemoryIds, flow, request.environment(), contextHashes);
        var workingNodes = working.stream().map(StepMemoryService.View::nodeId).collect(java.util.stream.Collectors.toSet());
        var relevantMemories = new ArrayList<>(memoryViews.stream().filter(m -> upstream.contains(m.nodeId()) && m.state().equals("CONFIRMED") && !workingNodes.contains(m.nodeId())).toList());
        working.stream().filter(m -> upstream.contains(m.nodeId())).forEach(relevantMemories::add);
        memoryViews.stream().filter(m -> upstream.contains(m.nodeId()) && m.state().equals("STALE"))
                .forEach(m -> warnings.add("上游步骤 " + node(flow, m.nodeId()).label() + " 的记忆已过期，本次未使用"));
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("node", focus); path.put("initialContext", flow.initialContext()); path.put("contextValues", request.contextValues());
        path.put("editableInputs", request.editableInputs());
        var availableContext = FlowBindingException.available(flow, focus.id());
        path.put("availableContext", availableContext);
        var bindingOptions = new TreeMap<String, Object>();
        request.editableInputs().forEach(key -> bindingOptions.put(key, Map.of("expectedType", operation.inputs().get(key).type(),
                "contextKeys", FlowBindingException.matching(availableContext, operation.inputs().get(key).type()))));
        path.put("inputBindingOptions", bindingOptions);
        path.put("bindingPolicy", "contextKeys 仅通过类型筛选，不代表业务语义匹配。只有契约、源码或业务目标证明用途一致才能绑定；否则留空并说明缺口。可用 preparation.headers 添加鉴权头，无需在 editableInputs 中声明。");
        var runtimeReferences = new TreeSet<String>();
        flow.nodes().forEach(n -> {
            n.inputs().values().stream().filter(b -> "secret".equals(b.source())).forEach(b -> runtimeReferences.add(String.valueOf(b.value())));
            io.yuan.octopus.preparation.PreparationTools.values(n.preparation()).stream()
                    .filter(v -> "secret".equals(v.source())).forEach(v -> runtimeReferences.add(String.valueOf(v.value())));
        });
        path.put("runtimeReferences", runtimeReferences);
        path.put("reservedOutputKeys", flow.nodes().stream().flatMap(n -> n.outputs().stream()).map(Output::key).toList());
        path.put("upstreamOutputs", flow.nodes().stream().filter(n -> upstream.contains(n.id())).map(n -> Map.of("nodeId", n.id(), "label", n.label(), "outputs", n.outputs())).toList());
        path.put("workflow", flow.nodes().stream().map(n -> Map.of("id", n.id(), "label", n.label(), "method", selected.get(n.operationRef()).method(), "path", selected.get(n.operationRef()).path())).toList());
        path.put("dependencies", flow.dependencies());
        if(flow.steps()!=null) path.put("steps",flow.steps().stream().map(g -> Map.of("id",g.id(),"mode",g.mode(),"callIds",g.calls().stream().map(FlowNode::id).toList())).toList());
        path.put("testPoints", flow.testPoints().stream().filter(p -> p.covers().contains(focus.id())).map(p -> Map.of("id", p.id(), "title", p.title(),
                "assertions", p.assertions().getOrDefault(focus.id(), List.of()), "inputOverrides", p.inputOverrides().getOrDefault(focus.id(), Map.of()))).toList());
        var modelContext = new LinkedHashMap<String, Object>(); modelContext.put("goal", request.goal());
        modelContext.put("path", FlowFillValidator.modelPath(path)); modelContext.put("operation", operation);
        modelContext.put("background", backgrounds.get(focus.id()).values()); modelContext.put("sources", source);
        modelContext.put("coverage", Map.of("provided", List.of("当前 API 契约", "所列版本源码片段", "人工项目/模块背景", "background.repositoryAnalysis 中已有的仓库归纳与引用；不存在即尚未分析"), "unresolved", List.of("部署环境动态配置", "运行时密钥材料、部署算法配置及扩展安装状态"), "limitation", COVERAGE_LIMIT));
        modelContext.put("preparationTools",io.yuan.octopus.preparation.PreparationTools.ALL);
        // FR-FN-20: JMeter built-ins and uploaded extensions are discovered on demand, never injected wholesale.
        var functionLoop = functionCatalog == null ? null : new io.yuan.octopus.scenario.extension.FunctionToolLoop(functionCatalog);
        if (functionLoop != null) modelContext.put("functionTools", io.yuan.octopus.scenario.extension.FunctionToolLoop.definitions());
        modelContext.put("preparationPlanFormat", Map.of(
                "binding",Map.of("requiredFields",List.of("source","value"),"sourceEnum",List.of("literal","context","secret","result","input","request")),
                "parameterExamples",Map.of("algorithm",Map.of("source","literal","value","MD5"),"timestamp",Map.of("source","result","value","clock.value")),
                "example",new io.yuan.octopus.preparation.PreparationPlan(List.of(
                        new io.yuan.octopus.preparation.PreparationPlan.Call("clock","time.now","1",Map.of()),
                        new io.yuan.octopus.preparation.PreparationPlan.Call("clockText","string.value","1",Map.of("value",new io.yuan.octopus.preparation.PreparationPlan.Value("result","clock.value")))),
                        null,Map.of("timestamp",new io.yuan.octopus.preparation.PreparationPlan.Value("result","clockText.value"))),
                "instruction","示例仅说明格式，不代表当前 API 必须采用这些工具或算法。每个工具 inputs 参数值、body、headers 值都是 {source,value} 对象，不是裸字符串。"));
        modelContext.put("upstreamMemories", relevantMemories); modelContext.put("warnings", warnings);
        int formatCorrections = 0; int patchCorrections = 0;
        for (;;) {
            var response = llm.step(request.model(), new LinkedHashMap<>(modelContext), deadline.next(STEP_CALL));
            var toolCalls = functionLoop == null ? null : io.yuan.octopus.scenario.extension.FunctionToolLoop.toolCalls(response.content());
            if (toolCalls != null) {
                if (functionLoop.exhausted()) {
                    modelContext.put("toolFeedback", "工具调用已达上限（" + io.yuan.octopus.scenario.extension.FunctionToolLoop.MAX_CALLS + " 次），请直接返回完整补全 JSON");
                    if (formatCorrections++ >= 2) throw new FlowFailure(FlowFailure.Code.MODEL_RESPONSE_INVALID);
                    continue;
                }
                functionLoop.execute(toolCalls);
                modelContext.put("toolResults", functionLoop.results());
                continue;
            }
            if (functionLoop != null && functionLoop.calls() > 0 && !warnings.contains("函数发现：" + functionLoop.calls() + " 次工具调用"))
                warnings.add("函数发现：" + functionLoop.calls() + " 次工具调用");
            Patch patch; Flow filled; StepMemoryService.Memory memory;
            try {
                patch = StepModelJson.parse(response);
            } catch(FlowFailure invalid) {
                if(invalid.code != FlowFailure.Code.MODEL_RESPONSE_INVALID || formatCorrections >= 2)throw invalid;
                formatCorrections++;
                modelContext.put("formatFeedback",Map.of("attempt",formatCorrections,"message",invalid.getMessage(),
                        "instruction","从同一原始节点重新生成完整 JSON 对象；只修正格式，不返回说明、代码围栏或额外字段。所有 preparation.tools[].inputs 参数及 headers 值必须使用 {source,value} 对象，参考 preparationPlanFormat，不能直接填写字符串或数字。"));
                warnings.add("模型返回格式未通过校验，已进行第 " + formatCorrections + " 次格式修正：" + invalid.getMessage());
                continue;
            }
            try {
                require(patch.node() != null && focus.id().equals(patch.node().id()) && patch.assertions() != null, "模型只能修改当前步骤");
                require(patch.assertions().stream().allMatch(a -> focus.id().equals(a.nodeId())), "模型不能修改其他步骤的断言");
                patch = withoutUnchanged(patch,flow,focus);
                var currentPatch = patch;
                var fills = flow.nodes().stream().map(n -> n.id().equals(focus.id()) ? currentPatch.node() : new FlowSuggestionService.NodeFill(n.id(), Map.of(), List.of())).toList();
                filled = FlowFillValidator.apply(validation, new FlowSuggestionService.Patch(fills, patch.assertions(), patch.assumptions(), patch.questions()), selected);
                memory = memory(patch.memory(), node(filled, focus.id()), source, request);
            } catch (RuntimeException e) {
                // One bounded semantic correction; never retain or replay rejected raw model data.
                String safeReason = FlowFailure.safeValidationReason(e);
                if (patchCorrections == 0 && safeReason != null) {
                    patchCorrections++;
                    if(e instanceof FlowBindingException binding) {
                        modelContext.put("validationFeedback", binding.issue);
                        warnings.add("模型首次绑定校验未通过，已按字段诊断重新生成一次：" + binding.getMessage());
                    } else {
                        modelContext.put("patchFeedback", Map.of("message",safeReason,"instruction",
                                "从同一原始节点重新生成完整候选，修正契约或预处理校验问题。不得生成敏感字面量或占位凭据；无法确定的数据保留为待确认问题。"));
                        warnings.add("模型首次契约校验未通过，已按诊断重新生成一次：" + safeReason);
                    }
                    continue;
                }
                throw FlowFailure.validation(e);
            }
            var proposal = memories.propose(filled, focus.id(), request.environment(), contextHashes, response.model(), memory);
            return new Suggestion(filled, List.of(operation), response.model(), patch.assumptions(), patch.questions().stream().map(ModelKnowledgePolicy::question).distinct().toList(), warnings.stream().distinct().toList(), hash(flow), focus.id(), proposal);
        }
    }
    /** Models sometimes echo existing configuration. Only exact matches are no-ops, never replacements. */
    private static Patch withoutUnchanged(Patch patch,Flow flow,FlowNode focus) {
        var inputs=new LinkedHashMap<>(patch.node().inputs());
        inputs.entrySet().removeIf(e->focus.inputs().containsKey(e.getKey()) && canonical(e.getValue()).equals(canonical(focus.inputs().get(e.getKey()))));
        var outputs=patch.node().outputs().stream().filter(o->!focus.outputs().contains(o)).distinct().toList();
        var assertions=patch.assertions().stream().filter(a->flow.testPoints().stream().noneMatch(p->p.id().equals(a.pointId())
                && p.assertions().getOrDefault(a.nodeId(),List.of()).stream().anyMatch(old->canonical(old).equals(canonical(a.assertion()))))).distinct().toList();
        return new Patch(new FlowSuggestionService.NodeFill(patch.node().id(),inputs,outputs,patch.node().preparation()),assertions,patch.memory(),patch.assumptions(),patch.questions());
    }
    public StepMemoryService.View confirm(Confirmation request) {
        require(request.memoryId() != null, "请选择步骤记忆");
        var validation = new FlowSuggestionService.Request(request.flow(), request.environment(), "确认步骤", null, Map.of(), Map.of());
        ProjectContextService.environment(request.environment()); FlowFillValidator.validateRequest(validation);
        return memories.confirm(request.memoryId(), request.flow(), request.environment(), hashes(backgrounds(request.flow(), request.environment(), selected(request.flow()))));
    }
    List<StepMemoryService.View> confirmChain(Flow flow, String environment, List<String> ids) {
        validate(flow, flow.nodes().getFirst().id(), environment);
        return memories.confirmChain(ids, flow, environment, hashes(backgrounds(flow, environment, selected(flow))));
    }
    private static void validate(Flow flow, String id, String environment) {
        ProjectContextService.environment(environment);
        FlowFillValidator.validateRequest(new FlowSuggestionService.Request(flow, environment, "逐步补全", null, Map.of(), Map.of()));
        require(id != null && flow.nodes().stream().anyMatch(n -> n.id().equals(id)), "请选择当前步骤");
    }
    private static FlowNode node(Flow flow, String id) { return flow.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow(); }
    private static List<String> upstream(Flow flow, String id) {
        var ids = FlowFillValidator.ancestors(flow).get(id); return flow.nodes().stream().map(FlowNode::id).filter(ids::contains).toList();
    }
    private Map<String, Operation> selected(Flow flow) {
        Map<String, Operation> result = new LinkedHashMap<>(); flow.nodes().forEach(n -> result.computeIfAbsent(n.operationRef(), knowledge::operation)); return result;
    }
    private List<RepositoryCatalogClient.SourceEvidence> sources(Operation operation, List<String> warnings) {
        if (operation.requestSchema() != null && operation.requestSchema().get("x-octopus-contract-warnings") instanceof List<?> issues) issues.forEach(w -> warnings.add(String.valueOf(w)));
        if (operation.implementations().size() != 1) { warnings.add("当前 API 未关联唯一源码版本，本次使用接口契约与项目背景"); return List.of(); }
        var impl = operation.implementations().getFirst();
        try {
            var source = catalog.sourceContext(impl, operation.method(), operation.path()); var c = source.contract();
            require(c.projectId() == impl.projectId() && c.commitId().equals(impl.commitId()) && c.branch().equals(impl.branch())
                    && c.signature().replaceAll("\\s+", "").equals(impl.signature().replaceAll("\\s+", "")) && c.path().equals(operation.path()) && c.httpMethod().equals(operation.method()), "源码版本与所选 API 不符");
            require(source.sources().stream().allMatch(s -> s.commitId().equals(impl.commitId())), "源码片段版本不一致");
            warnings.addAll(source.warnings().stream().map(w->"API 局部源码："+w).toList()); return source.sources();
        } catch (IllegalStateException e) { warnings.add("当前版本源码暂不可读取，本次仅使用已有契约与背景；可以稍后重试"); return List.of(); }
    }
    private Map<String, Background> backgrounds(Flow flow, String environment, Map<String, Operation> selected) {
        var scopes = selected.values().stream().anyMatch(o -> !o.implementations().isEmpty()) ? catalog.scopes() : List.<RepositoryCatalogClient.Scope>of();
        Map<String, Background> cache = new HashMap<>(); Map<String, Background> result = new LinkedHashMap<>();
        for (var node : flow.nodes()) result.put(node.id(), cache.computeIfAbsent(node.operationRef(), ref -> background(selected.get(ref), environment, scopes)));
        return result;
    }
    private Background background(Operation operation, String environment, List<RepositoryCatalogClient.Scope> scopes) {
        var values = new ArrayList<Map<String, Object>>(); var versions = new ArrayList<Object>();
        var repositorySources=new LinkedHashMap<String,RepositoryCatalogClient.SourceEvidence>();
        for (var impl : operation.implementations()) for (var scope : scopes) for (var module : scope.modules()) if (module.projectId() == impl.projectId()) {
            var root = contexts.resolve(scope.key(), "", environment); var own = contexts.resolve(scope.key(), module.modulePath(), environment);
            var value = new LinkedHashMap<>(contexts.modelContext(scope.key(), module.modulePath(), environment));
            value.put("project", scope.name()); value.put("projectId", module.projectId());
            value.put("repositoryKey",scope.key()); value.put("branch",impl.branch()); value.put("commitId",impl.commitId());
            value.put("projectBackground", Map.of("description", root.own().definition().description(), "businessRules", root.own().definition().businessRules()));
            value.put("moduleBackground", Map.of("description", own.own().definition().description(), "businessRules", own.own().definition().businessRules()));
            var analysis=repositoryAnalysis==null?null:repositoryAnalysis.reference(scope.key(),impl.branch(),impl.commitId(),environment);
            if(analysis!=null) {
                value.put("repositoryAnalysis",Map.of("id",analysis.id(),"branch",impl.branch(),"commitId",impl.commitId(),"environment",environment,"basis","AI依据仓库源码归纳，需结合规则的适用模块与不确定项；人工背景优先","report",analysis.report(),"warnings",analysis.warnings()));
                var ids=analysis.report().rules().stream().flatMap(r->r.evidenceIds().stream()).collect(java.util.stream.Collectors.toSet());
                int snippetLimit=Math.min(3000,72000/Math.max(1,ids.size()));
                analysis.sources().stream().filter(s->ids.contains(s.id())).forEach(s->repositorySources.putIfAbsent(s.id(),new RepositoryCatalogClient.SourceEvidence(s.id(),s.projectId(),s.commitId(),s.filePath(),s.lineStart(),s.lineEnd(),s.kind(),s.text().substring(0,Math.min(snippetLimit,s.text().length())),s.truncated() || s.text().length()>snippetLimit)));
                versions.add(hash(analysis.report()));
            } else value.put("repositoryAnalysisStatus","此版本尚未进行仓库背景分析，无法仅从 Controller 判定完整入口规则");
            values.add(value);
            // Configuration versions and runtime URLs participate in invalidation, never in the model payload.
            versions.add(Arrays.asList(scope.key(), module.modulePath(), root.own().version(), own.own().version(), own.effective()));
        }
        return new Background(values, hash(Arrays.asList(operation.ref(), values, versions, io.yuan.octopus.preparation.PreparationTools.ALL)),List.copyOf(repositorySources.values()));
    }
    private static List<RepositoryCatalogClient.SourceEvidence> mergeSources(List<RepositoryCatalogClient.SourceEvidence> local,List<RepositoryCatalogClient.SourceEvidence> repository) {
        var sources=new LinkedHashMap<String,RepositoryCatalogClient.SourceEvidence>();local.forEach(s->sources.put(s.id(),s));repository.forEach(s->sources.putIfAbsent(s.id(),s));return sources.values().stream().limit(180).toList();
    }
    private static Map<String, String> hashes(Map<String, Background> values) {
        var result = new TreeMap<String, String>(); values.forEach((k, v) -> result.put(k, v.hash())); return result;
    }
    private static StepMemoryService.Memory memory(StepMemoryService.MemoryDraft draft, FlowNode node, List<RepositoryCatalogClient.SourceEvidence> source, Request request) {
        require(draft != null && draft.summary() != null && draft.summary().length() <= 3000 && draft.decisions() != null && draft.questions() != null && draft.evidenceIds() != null, "步骤记忆不完整");
        for (var lines : List.of(draft.decisions(), draft.questions())) { require(lines.size() <= 20, "步骤记忆过长"); lines.forEach(s -> require(s != null && s.length() <= 1600, "步骤记忆条目过长")); }
        require(draft.evidenceIds().size() <= 20, "记忆源码引用过多");
        require(source.stream().map(RepositoryCatalogClient.SourceEvidence::id).toList().containsAll(draft.evidenceIds()), "记忆引用了未提供的源码");
        var refs = source.stream().filter(s -> draft.evidenceIds().contains(s.id())).map(s -> new StepMemoryService.Evidence(s.id(), s.projectId(), s.commitId(), s.filePath(), s.lineStart(), s.lineEnd(), s.kind())).toList();
        var bindings = new ArrayList<>(node.inputs().entrySet().stream().map(e -> e.getKey() + " ← " + switch (e.getValue().source()) {
            case "context" -> "上下文 " + e.getValue().value(); case "secret" -> "运行时敏感值引用"; default -> "固定测试值（具体值不存入记忆）";
        }).toList());
        if(node.preparation()!=null) node.preparation().tools().forEach(t -> bindings.add("请求预处理："+t.tool()+"@"+t.version()+" → "+t.id()));
        // Do not retain supplied test values in narrative memories, even if a model repeats them.
        var protectedValues = new HashSet<String>(); collectValues(request.contextValues(), protectedValues);
        request.flow().nodes().forEach(n -> n.inputs().values().stream().filter(b -> "literal".equals(b.source())).forEach(b -> collectValues(b.value(), protectedValues)));
        request.flow().nodes().forEach(n -> io.yuan.octopus.preparation.PreparationTools.values(n.preparation()).stream().filter(v -> "literal".equals(v.source())).forEach(v -> collectValues(v.value(),protectedValues)));
        java.util.function.Function<String, String> scrub = text -> { String result = text; for (String value : protectedValues) result = result.replace(value, "[测试值已省略]"); return result; };
        var questions = new ArrayList<>(draft.questions().stream().map(scrub).map(ModelKnowledgePolicy::question).toList()); questions.add(COVERAGE_LIMIT);
        return new StepMemoryService.Memory(scrub.apply(draft.summary()), draft.decisions().stream().map(scrub).toList(), questions.stream().distinct().toList(), refs, bindings, node.outputs(), node.operationRef());
    }
    private static void collectValues(Object value, Set<String> values) {
        if (value instanceof String s && s.length() >= 6) values.add(s);
        else if (value instanceof Map<?, ?> m) m.values().forEach(v -> collectValues(v, values));
        else if (value instanceof List<?> l) l.forEach(v -> collectValues(v, values));
    }
}
