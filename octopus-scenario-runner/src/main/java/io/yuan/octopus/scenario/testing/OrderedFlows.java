package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.*;
import java.util.*;

/** Version 0.2 has positional references, never inferred scheduling. */
public final class OrderedFlows {
    private OrderedFlows() {}

    public static List<FlowStep> prefix(Flow flow, TestPoint point) {
        if (flow.steps() == null) {
            require(point.endAtStepRef() == null, "Static prefixes require an ordered flow");
            return null;
        }
        require(!flow.steps().isEmpty() && flow.steps().size() <= 100, "Flow must contain 1..100 steps");
        Set<String> ids = new HashSet<>(), calls = new HashSet<>();
        int end = -1;
        for (int i = 0; i < flow.steps().size(); i++) {
            var step = flow.steps().get(i);
            identifier(step.id(), "flow step id");
            require(ids.add(step.id()), "Duplicate flow step: " + step.id());
            require(Set.of("SERIAL", "PARALLEL").contains(step.mode()), "Step mode must be SERIAL or PARALLEL");
            require(step.calls() != null && !step.calls().isEmpty(), "Step calls must not be empty: " + step.id());
            for (var call : step.calls()) require(calls.add(call.id()), "Duplicate call: " + call.id());
            if (step.id().equals(point.endAtStepRef())) end = i;
        }
        require(calls.size() <= 300, "Flow exceeds 300 calls");
        require(point.endAtStepRef() == null || end >= 0, "Unknown endAtStepRef");
        return point.endAtStepRef() == null ? flow.steps() : flow.steps().subList(0, end + 1);
    }

    public static List<Step> validate(TestIR ir, Map<String, Set<String>> dependencies) {
        require(ir.dependencies().isEmpty(), "Ordered IR does not accept dependency edges");
        var groups = ir.executionSteps();
        require(groups != null && !groups.isEmpty() && groups.size() <= 100, "Ordered IR requires 1..100 execution steps");
        Map<String,Step> calls = new LinkedHashMap<>();
        ir.steps().forEach(s -> calls.put(s.id(), s));
        Set<String> seen = new HashSet<>(), groupIds = new HashSet<>(), available = new HashSet<>();
        List<Step> result = new ArrayList<>();
        Map<String,String> types=new HashMap<>();ir.initialContext().forEach((key,value)->types.put(key,value.type()));
        for (var group : groups) {
            identifier(group.id(), "flow step id");
            require(groupIds.add(group.id()), "Duplicate flow step: " + group.id());
            require(Set.of("SERIAL", "PARALLEL").contains(group.mode()), "Unsupported group mode");
            require(group.callIds() != null && !group.callIds().isEmpty(), "Empty call group");
            for (String id : group.callIds()) {
                require(calls.containsKey(id) && seen.add(id), "Unknown or duplicate grouped call: " + id);
                require(available.containsAll(dependencies.get(id)),
                        "Call " + id + " references itself, a later call, or a parallel sibling");
                result.add(calls.get(id));
                if ("SERIAL".equals(group.mode())) available.add(id);
            }
            available.addAll(group.callIds());
            for(String id:group.callIds()) calls.get(id).outputs().forEach(o->types.put(o.key(),o.type()));
            Set<String> checks=new HashSet<>();
            for(var check:group.assertions()) {
                identifier(check.id(),"step assertion id");
                require(checks.add(check.id()) && types.containsKey(check.leftKey()) && types.containsKey(check.rightKey()),"Step assertion references unavailable outputs");
                require(types.get(check.leftKey()).equals(types.get(check.rightKey())),"Step assertion type mismatch");
            }
        }
        require(seen.equals(calls.keySet()), "Every call must belong to exactly one step");
        require(result.equals(ir.steps()), "IR call order disagrees with execution steps");
        return List.copyOf(result);
    }
}
