package io.yuan.octopus.scenario.testing;

import java.util.*;
import java.util.function.Function;
import org.apache.jmeter.assertions.JSR223Assertion;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.SwitchController;
import org.apache.jmeter.control.WhileController;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.java.sampler.JSR223Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jorphan.collections.HashTree;

/**
 * FR-LIN-06: checks a compiled JMX tree before it is serialized. Only the compiler's fixed scaffolding is
 * allowed: single-iteration thread groups (dispatch loops sized to their stage), the call selector, HTTP
 * samplers, header managers, fixed waits, platform script templates byte-for-byte, and While controllers only
 * for steps that poll under an authorization. Anything else — other logic controllers, scripts that are not
 * the platform templates, retries — fails compilation.
 */
public final class JmxLinearGuard {
    public static final Set<String> TEMPLATES = Set.of("octopus-dispatch-v1", "octopus-guard-v1", "octopus-prepare-v1",
            "octopus-assert-v1", "octopus-verify-v1", "octopus-complete-v1");
    private static final Set<Class<?>> ALLOWED = Set.of(TestPlan.class, ThreadGroup.class, JSR223Sampler.class, JSR223Assertion.class,
            HTTPSamplerProxy.class, HeaderManager.class, ConstantTimer.class, SwitchController.class, GenericController.class, WhileController.class);

    private JmxLinearGuard() {}

    /**
     * @param pollSteps step ids that poll (each under a verified authorization)
     * @param template  cache key → exact script text of the platform template
     */
    public static void check(HashTree tree, Set<String> pollSteps, Function<String, String> template) {
        var violations = new ArrayList<String>();
        walk(tree, pollSteps, template, violations, 0);
        if (!violations.isEmpty())
            throw new IllegalArgumentException("编译产物违反严格线性约束（FR-LIN-06）：" + String.join("；", violations.subList(0, Math.min(5, violations.size()))));
    }

    private static void walk(HashTree tree, Set<String> polls, Function<String, String> template, List<String> violations, int depth) {
        if (depth > 12) {
            violations.add("测试计划嵌套过深");
            return;
        }
        for (Object key : tree.list()) {
            if (!(key instanceof TestElement)) {
                violations.add("未知元素 " + key.getClass().getName());
                continue;
            }
            check((TestElement) key, polls, template, violations);
            walk(tree.getTree(key), polls, template, violations, depth + 1);
        }
    }

    private static void check(TestElement e, Set<String> polls, Function<String, String> template, List<String> violations) {
        String name = e.getName() == null ? "" : e.getName();
        if (!ALLOWED.contains(e.getClass())) {
            violations.add("不允许的元素 " + e.getClass().getSimpleName() + "（" + name + "）");
            return;
        }
        if (e instanceof ThreadGroup) {
            var group = (ThreadGroup) e;
            if (group.getNumThreads() < 1 || group.getNumThreads() > 4) violations.add("线程组 " + name + " 的线程数超出 1–4");
            var controller = group.getSamplerController();
            if (!(controller instanceof LoopController)) violations.add("线程组 " + name + " 必须由固定次数的循环驱动");
            else {
                var loop = (LoopController) controller;
                if (loop.getLoops() < 1 || loop.getLoops() > 300) violations.add("线程组 " + name + " 的迭代次数无效");
            }
        } else if (e instanceof WhileController) {
            String id = name.startsWith("poll:") ? name.substring(5) : null;
            if (id == null || !polls.contains(id) || !("${oct.poll." + id + ".continue}").equals(((WhileController) e).getCondition()))
                violations.add("While 控制器只允许用于已授权轮询的步骤（" + name + "）");
        } else if (e instanceof SwitchController) {
            if (!"Select allocated call".equals(name) || !"${oct.call.index}".equals(((SwitchController) e).getSelection()))
                violations.add("不允许的 Switch 控制器（" + name + "）");
        } else if (e instanceof GenericController) {
            if (!name.startsWith("call:")) violations.add("不允许的逻辑控制器（" + name + "）");
        } else if (e instanceof ConstantTimer) {
            boolean wait = name.startsWith("wait:");
            boolean pollWait = name.startsWith("poll-wait:") && polls.contains(name.substring(10));
            if (!wait && !pollWait) violations.add("不允许的定时器（" + name + "）");
        } else if (e instanceof JSR223Sampler || e instanceof JSR223Assertion) {
            String key = e.getPropertyAsString("cacheKey");
            String expected = TEMPLATES.contains(key) ? template.apply(key) : null;
            if (expected == null || !expected.equals(e.getPropertyAsString("script")) || !"groovy".equals(e.getPropertyAsString("scriptLanguage"))
                    || !e.getPropertyAsString("filename").isEmpty())
                violations.add("脚本 " + name + " 不是平台固定模板");
        }
    }
}
