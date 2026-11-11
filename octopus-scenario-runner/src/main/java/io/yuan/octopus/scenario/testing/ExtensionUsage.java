package io.yuan.octopus.scenario.testing;

import io.yuan.octopus.extension.runtime.ExtensionTools;
import io.yuan.octopus.preparation.PreparationPlan;
import java.util.*;

/**
 * Which JMeter functions a plan uses: {@link #uses} covers uploaded extensions and whitelisted built-ins
 * (both need the {@code fn.ext} capability); {@link #keys} lists only uploaded versions, which are
 * fingerprint-checked before a run.
 */
public final class ExtensionUsage {
    public static final String CAPABILITY = "fn.ext";

    private ExtensionUsage() {}

    public static boolean uses(PreparationPlan plan) {
        return plan != null && plan.tools() != null && plan.tools().stream().anyMatch(c -> ExtensionTools.isExtension(c.tool()));
    }

    public static Set<String> keys(PreparationPlan plan) {
        var keys = new TreeSet<String>();
        if (plan != null && plan.tools() != null)
            plan.tools().stream().filter(c -> ExtensionTools.isUploaded(c.tool())).forEach(c -> keys.add(c.tool() + "@" + c.version()));
        return keys;
    }

    public static Set<String> keys(TestingModel.TestIR ir) {
        var keys = new TreeSet<String>();
        ir.steps().forEach(s -> keys.addAll(keys(s.preparation())));
        if (ir.cleanup() != null) ir.cleanup().forEach(c -> keys.addAll(keys(c.call().preparation())));
        return keys;
    }
}
