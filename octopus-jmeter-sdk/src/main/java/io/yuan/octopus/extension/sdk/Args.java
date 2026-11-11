package io.yuan.octopus.extension.sdk;

import java.math.BigDecimal;
import java.util.*;

/** Typed, already-validated arguments passed to {@link OctopusFunction#compute}. */
public final class Args {
    private final Map<String, Object> values;

    Args(Map<String, Object> values) { this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }

    public boolean has(String name) { return values.get(name) != null; }
    public Object get(String name) { return values.get(name); }
    public String string(String name) { return (String) require(name); }
    public long integer(String name) { return ((BigDecimal) require(name)).longValueExact(); }
    public BigDecimal number(String name) { return (BigDecimal) require(name); }
    public boolean bool(String name) { return (Boolean) require(name); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> object(String name) { return (Map<String, Object>) require(name); }

    public Map<String, Object> asMap() { return values; }

    private Object require(String name) {
        Object v = values.get(name);
        if (v == null) throw new IllegalArgumentException("Missing argument: " + name);
        return v;
    }
}
