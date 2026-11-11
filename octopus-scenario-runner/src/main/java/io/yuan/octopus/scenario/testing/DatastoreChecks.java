package io.yuan.octopus.scenario.testing;

import io.yuan.octopus.verification.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import java.util.*;

/** Keeps datastore oracles in the existing reviewed TestPoint/IR, without changing old revision hashes. */
public final class DatastoreChecks {
    public static final String KIND = "DATASTORE_VERIFY";
    private DatastoreChecks() {}
    public static VerificationPlan plan(Assertion assertion) {
        var plan = read(assertion.expected(), VerificationPlan.class);
        VerificationRules.validate(plan); return plan;
    }
    public static String capability(VerificationPlan p) { return "verify." + p.kind().toLowerCase(Locale.ROOT); }
    public static String key(VerificationPlan.Source source) { return hash(source); }
    public static List<Assertion> of(Step step) { return step.assertions().stream().filter(a -> KIND.equals(a.kind())).toList(); }
    public static void bindings(VerificationPlan p, String step, Map<String,Variable> variables,
                                Map<String,String> producers, Set<String> dependencies) {
        for (var value : VerificationRules.values(p)) if ("context".equals(value.source())) {
            String key = String.valueOf(value.value()); var declaration = variables.get(key);
            IrValidator.require(declaration != null && Set.of("string", "integer", "number", "boolean").contains(declaration.type()), "验证变量不存在或不是普通值: " + key);
            String producer = producers.get(key);
            // These checks execute after HTTP response extraction, so same-step outputs are available.
            if (producer != null && !producer.equals(step)) dependencies.add(producer);
        }
    }
}
