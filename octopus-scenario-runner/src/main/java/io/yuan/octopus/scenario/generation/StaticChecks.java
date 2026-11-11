package io.yuan.octopus.scenario.generation;

import static io.yuan.octopus.scenario.generation.GenerationModel.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;

import io.yuan.octopus.scenario.testing.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Operator 的静态校验（OPER-001）：与正式运行预检相同的顺序——候选 IR（含全部确认规则）→ 轮询授权 →
 * 指定环境时解析环境并试编译 JMX（触发线性守卫），不执行。
 */
@Component
public class StaticChecks {
    private final ScenarioPlanner planner;
    private final BusinessKnowledgeStore store;
    @Autowired(required = false) private PollAuthorizationService polls;
    @Autowired(required = false) private EnvironmentBindings environments;
    @Autowired(required = false) private ExecutionDefaultsService defaults;
    @Autowired(required = false) private JmxCompiler compiler;

    @Autowired
    public StaticChecks(ScenarioPlanner planner, BusinessKnowledgeStore store) {
        this.planner = planner;
        this.store = store;
    }

    StaticChecks(ScenarioPlanner planner, BusinessKnowledgeStore store, PollAuthorizationService polls, EnvironmentBindings environments,
                 ExecutionDefaultsService defaults, JmxCompiler compiler) {
        this(planner, store);
        this.polls = polls;
        this.environments = environments;
        this.defaults = defaults;
        this.compiler = compiler;
    }

    public record Checked(StaticResult result, TestIR ir, ExecutionProfile profile) {}

    /**
     * @param probe 探测配置；mode=ON 且环境非空时才解析环境并试编译
     */
    public Checked check(FlowRevision confirmed, TestPoint candidate, List<String> materializationIssues, ProbeConfig probe) {
        List<String> errors = new ArrayList<>();
        boolean materialization = false;
        for (String issue : materializationIssues) if (issue.startsWith("LEAK:") || issue.startsWith("数据位")) {
            errors.add(issue.startsWith("LEAK:") ? issue.substring(5) : issue);
            materialization = true;
        }
        TestIR ir = null;
        ExecutionProfile profile = null;
        try {
            ir = planner.planCandidate(confirmed, candidate);
            if (polls != null) polls.verify(ir, store::operation);
        } catch (IllegalArgumentException | IllegalStateException e) {
            String message = e.getMessage() == null ? "候选未通过校验" : e.getMessage();
            errors.add(message);
            materialization |= materializationMessage(message);
        }
        if (ir != null && errors.isEmpty() && probe != null && probe.mode() == ProbeMode.ON && probe.environment() != null
                && compiler != null && defaults != null) {
            try {
                var resolved = defaults.resolve(ir, probe.environment());
                Map<String, Target> services = new TreeMap<>(resolved.services());
                services.putAll(probe.services());
                Map<String, Object> context = new TreeMap<>(resolved.initialContext());
                context.putAll(probe.initialContext());
                profile = new ExecutionProfile(services, context, probe.secretEnvironmentNames(), probe.environment());
                if (environments != null) profile = environments.resolve(ir, profile);
                compiler.compile(ir, profile);
            } catch (IllegalArgumentException | IllegalStateException e) {
                errors.add("探测环境配置或编译失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                profile = null;
            }
        }
        return new Checked(new StaticResult(errors.isEmpty(), List.copyOf(errors), ir == null ? null : hash(ir), materialization), errors.isEmpty() ? ir : null, profile);
    }

    /** 数据或绑定类错误可由 Planner 换数据修复；其余（契约、断言结构、流程定义）交给人工。 */
    static boolean materializationMessage(String message) {
        return message.startsWith("Literal input type mismatch") || message.startsWith("Undefined context") || message.startsWith("Context type mismatch")
                || message.startsWith("Missing input binding") || message.startsWith("Initial context missing");
    }

    static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** OPER-006 中与候选本身有关的条件；预算与轮次条件由任务服务判断。 */
    public static String ineligibility(StaticResult result, TestIR ir, Flow flow, ProbeEnvironmentService.Active env, ProbeConfig probe) {
        if (probe == null || probe.mode() != ProbeMode.ON) return "未开启探测";
        if (env == null) return "环境未开启生成探测或授权已撤销";
        if (!result.ok() || ir == null) return "静态校验未通过";
        if ("CLEANUP_REQUIRED".equals(env.dataPolicy())) {
            boolean writes = ir.steps().stream().map(s -> ir.operations().stream().filter(o -> o.ref().equals(s.operationRef())).findFirst().orElseThrow())
                    .anyMatch(o -> !SAFE_METHODS.contains(String.valueOf(o.method()).toUpperCase(Locale.ROOT)));
            if (writes && (flow.cleanup() == null || flow.cleanup().isEmpty())) return "缺少清理清单：环境要求每次探测执行已审阅的清理";
        }
        return null;
    }
}
