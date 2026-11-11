package io.yuan.octopus.preparation;

import java.util.List;
import java.util.Map;

/** Data-only, versioned runtime calls. No scripts, function expressions or credential values. */
public record PreparationPlan(List<Call> tools, Value body, Map<String, Value> headers) {
    public record Value(String source, Object value) {}
    public record Call(String id, String tool, String version, Map<String, Value> inputs) {}
}
