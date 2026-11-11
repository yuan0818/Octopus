package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.WhileController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.java.sampler.JSR223Sampler;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

/** FR-LIN-06: the compiled plan may only contain the compiler's fixed, linear scaffolding. */
class JmxLinearGuardTest {
    private static final String GUARD = "// guard template";

    private static HashTree call(ListedHashTree tree) {
        var loop = new LoopController();
        loop.setLoops(1);
        var group = new ThreadGroup();
        group.setName("Scenario");
        group.setNumThreads(1);
        group.setSamplerController(loop);
        var plan = tree.add(new TestPlan("p"));
        var g = plan.add(group);
        var c = new GenericController();
        c.setName("call:c1");
        var callTree = g.add(c);
        var script = new JSR223Sampler();
        script.setName("guard:c1");
        script.setProperty("scriptLanguage", "groovy");
        script.setProperty("script", GUARD);
        script.setProperty("cacheKey", "octopus-guard-v1");
        script.setProperty("filename", "");
        callTree.add(script);
        var http = new HTTPSamplerProxy();
        http.setName("step:c1");
        callTree.add(http);
        return callTree;
    }

    private static void check(ListedHashTree tree, Set<String> polls) {
        JmxLinearGuard.check(tree, polls, key -> key.equals("octopus-guard-v1") ? GUARD : null);
    }

    @Test
    void fixedScaffoldingPasses() {
        var tree = new ListedHashTree();
        call(tree);
        assertDoesNotThrow(() -> check(tree, Set.of()));
    }

    @Test
    void conditionalControllersAreRejected() {
        var tree = new ListedHashTree();
        var callTree = call(tree);
        var branch = new IfController("${x}");
        branch.setName("branch");
        callTree.add(branch);
        var e = assertThrows(IllegalArgumentException.class, () -> check(tree, Set.of()));
        assertTrue(e.getMessage().contains("IfController"));
    }

    @Test
    void whileOnlyForAuthorizedPollSteps() {
        var tree = new ListedHashTree();
        var callTree = call(tree);
        var poll = new WhileController();
        poll.setName("poll:c1");
        poll.setCondition("${oct.poll.c1.continue}");
        callTree.add(poll);
        assertThrows(IllegalArgumentException.class, () -> check(tree, Set.of()));
        assertDoesNotThrow(() -> check(tree, Set.of("c1")));
    }

    @Test
    void scriptsMustBeThePlatformTemplatesVerbatim() {
        var tree = new ListedHashTree();
        var callTree = call(tree);
        var rogue = new JSR223Sampler();
        rogue.setName("guard:c2");
        rogue.setProperty("scriptLanguage", "groovy");
        rogue.setProperty("script", GUARD + "\nRuntime.getRuntime().exec('x')");
        rogue.setProperty("cacheKey", "octopus-guard-v1");
        rogue.setProperty("filename", "");
        callTree.add(rogue);
        assertThrows(IllegalArgumentException.class, () -> check(tree, Set.of()));
    }
}
