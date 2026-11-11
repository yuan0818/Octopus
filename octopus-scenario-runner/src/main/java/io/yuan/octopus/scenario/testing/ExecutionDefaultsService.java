package io.yuan.octopus.scenario.testing;

import org.springframework.stereotype.Service;

import java.util.*;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

/**
 * 依据项目背景中保存的测试环境，为已固定的场景推导执行配置（服务地址与初始上下文）。
 * <p>
 * 从 {@code ProjectContextController} 抽出，供手工运行页面与批量回归（{@link RegressionBatchService}）共用。
 * 推导不出唯一地址或变量时返回 warnings，由调用方决定提示人工或跳过。
 */
@Service
public class ExecutionDefaultsService {
    public record DefaultsRequest(String scenarioId, String environment) {}
    public record Defaults(Map<String, Target> services, Map<String, Object> initialContext, List<String> warnings) {}

    private final ProjectContextService service;
    private final RepositoryCatalogClient catalog;
    private final BusinessTestExecutionService execution;

    public ExecutionDefaultsService(ProjectContextService service, RepositoryCatalogClient catalog, BusinessTestExecutionService execution) {
        this.service = service; this.catalog = catalog; this.execution = execution;
    }

    public Defaults resolve(DefaultsRequest request) {
        ProjectContextService.environment(request.environment());
        return resolve(execution.load(request.scenarioId()), request.environment());
    }

    /** 用例生成探测：对尚未确认为场景的候选 IR 推导同样的执行配置。 */
    public Defaults resolve(TestIR ir, String environment) {
        ProjectContextService.environment(environment);
        var request = new DefaultsRequest(ir.scenarioId(), environment);
        var scopes = catalog.scopes();
        Map<String, Map<String, ProjectContextService.EnvironmentVariable>> variableCandidates = new TreeMap<>();
        Map<String, Set<String>> candidates = new TreeMap<>();
        for (Operation operation : ir.operations()) {
            String key = operation.system() + "." + operation.service();
            Set<String> urls = candidates.computeIfAbsent(key, k -> new TreeSet<>());
            for (var impl : operation.implementations()) for (var scope : scopes) for (var module : scope.modules())
                if (module.projectId() == impl.projectId()) {
                    var effective = service.resolve(scope.key(), module.modulePath(), request.environment()).effective();
                    String url = effective.baseUrl();
                    if (!url.isBlank()) urls.add(url);
                    for (var variable : effective.variables()) if (ir.initialContext().containsKey(variable.key())) {
                        String identity = TestingJson.canonical(List.of(variable.type(), variable.value()));
                        variableCandidates.computeIfAbsent(variable.key(), ignored -> new TreeMap<>()).put(identity, variable);
                    }
                }
        }
        Map<String, Target> services = new TreeMap<>(); List<String> warnings = new ArrayList<>();
        if(ir.executionSteps()!=null) {
            var allCalls=new ArrayList<>(ir.steps());
            if(ir.cleanup()!=null) ir.cleanup().forEach(c->allCalls.add(c.call()));
            for(var call:allCalls) {
                var operation=ir.operations().stream().filter(o->o.ref().equals(call.operationRef())).findFirst().orElseThrow();
                Set<String> urls=new TreeSet<>();
                for(var impl:operation.implementations()) for(var scope:scopes) for(var module:scope.modules()) if(module.projectId()==impl.projectId()) {
                    String url=service.resolve(scope.key(),module.modulePath(),request.environment()).effective().baseUrl();
                    if(!url.isBlank()) urls.add(url);
                }
                if(urls.size()==1) services.put("call:"+call.id(),new Target(urls.iterator().next()));
                else warnings.add(call.id()+(urls.isEmpty()?"：未配置测试地址":"：存在多个测试地址，请明确选择"));
            }
        }
        candidates.forEach((key, urls) -> {
            if(ir.executionSteps()!=null) return;
            if (urls.size() == 1) services.put(key, new Target(urls.iterator().next()));
            else warnings.add(key + (urls.isEmpty() ? "：未配置测试地址" : "：存在多个测试地址，请明确选择"));
        });
        Map<String, Object> initialContext = new TreeMap<>();
        variableCandidates.forEach((key, values) -> {
            if (values.size() != 1) { warnings.add("环境变量 " + key + "：关联模块存在不同值或类型，请手动填写"); return; }
            var value = values.values().iterator().next();
            if (!ir.initialContext().get(key).type().equals(value.type())) {
                warnings.add("环境变量 " + key + "：与流程声明类型不匹配，请手动填写"); return;
            }
            initialContext.put(key, value.value());
        });
        return new Defaults(services, initialContext, warnings);
    }
}
