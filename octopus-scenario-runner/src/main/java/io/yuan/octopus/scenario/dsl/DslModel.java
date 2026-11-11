package io.yuan.octopus.scenario.dsl;

import java.util.*;

/**
 * Syntax tree of the YAML test DSL (DSL_REQUIREMENTS §5). Everything here is a <em>declared item</em>: it was
 * written by the user and is locked during translation (FR-DERIVE-20). Positions are 1-based.
 */
public final class DslModel {
    private DslModel() {}

    /** A lint finding. ERROR blocks translation; WARNING and INFO are advisory. */
    public static final class Diagnostic {
        public String severity;
        public String code;
        public String message;
        public int line;
        public int column;
        public String hint;

        public Diagnostic() {}

        public Diagnostic(String severity, String code, String message, int line, int column, String hint) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.line = line;
            this.column = column;
            this.hint = hint;
        }

        public boolean error() { return "ERROR".equals(severity); }

        @Override public String toString() { return severity + " " + code + " @" + line + ":" + column + " " + message; }
    }

    public static final class Document {
        public String feature;
        public String project;
        public String environment;
        public List<Step> background = new ArrayList<>();
        public List<Scenario> scenarios = new ArrayList<>();
        public List<Diagnostic> diagnostics = new ArrayList<>();

        public boolean hasErrors() { return diagnostics.stream().anyMatch(Diagnostic::error); }
    }

    public static final class Scenario {
        public String name;
        public String id;
        public List<String> tags = new ArrayList<>();
        public String description;
        /** Scenario inputs {@code ${input.x}}: name → type. */
        public Map<String, String> inputs = new LinkedHashMap<>();
        public List<Step> steps = new ArrayList<>();
        public List<Step> cleanup = new ArrayList<>();
        public int line;
    }

    public static final class Step {
        public int line;
        public int column;
        public String method;
        public String path;
        public String service;
        public String name;
        /** {@code as}: name of this step's response entity for later references. */
        public String alias;
        /** {@code with}: values for path parameters and top-level body fields (or query/header when unambiguous). */
        public Map<String, Value> with = new LinkedHashMap<>();
        public Map<String, Value> query = new LinkedHashMap<>();
        public Map<String, Value> headers = new LinkedHashMap<>();
        /** Complete request body override. */
        public Value body;
        /** {@code from}: upstream steps this one depends on (field mapping derived). */
        public List<String> from = new ArrayList<>();
        public List<Expect> expects = new ArrayList<>();
        /** Explicit status override ({@code status:}). */
        public Integer status;
        public long waitMillis;
        public PollSpec poll;
        /** Explicit role override: GIVEN, WHEN or THEN. */
        public String role;
        /** Whether the step was written as the bare {@code METHOD /path} shorthand. */
        public boolean shorthand;

        public String label() { return name != null ? name : method + " " + path; }
    }

    /** {@code poll: true} uses the API authorization's bounds; otherwise bounds no looser than the authorization. */
    public static final class PollSpec {
        public boolean useAuthorization;
        public Long everyMillis;
        public Long withinMillis;
        public int line;
    }

    /** One expectation. Precise kinds are locked as written; NATURAL and DATASTORE need derivation (I2). */
    public static final class Expect {
        public enum Kind { STATUS, JSON_EQUALS, JSON_EXISTS, SCHEMA, DATASTORE, NATURAL }

        public Kind kind;
        public String path;
        public Object expected;
        /** For DATASTORE: db, redis or kafka. */
        public String target;
        /** Original text, kept for NATURAL/DATASTORE and rendering. */
        public String text;
        public int line;
        public int column;
    }

    /** A value in {@code with}, {@code query}, {@code headers} or {@code body}. */
    public abstract static class Value {
        public int line;
        public int column;
    }

    /** A typed literal: String, Boolean, Long, java.math.BigDecimal or null. */
    public static final class Literal extends Value {
        public final Object value;
        public Literal(Object value) { this.value = value; }
    }

    /** {@code ${alias.path}}, {@code ${env.x}}, {@code ${secret.x}} or {@code ${input.x}}. */
    public static final class Ref extends Value {
        public enum Kind { STEP, ENV, SECRET, INPUT }

        public final Kind kind;
        /** Alias, or the environment / secret / input name. */
        public final String name;
        /** Field path after the alias, e.g. {@code .id} or {@code .items[0].sku}; empty for the whole entity. */
        public final String path;

        public Ref(Kind kind, String name, String path) {
            this.kind = kind;
            this.name = name;
            this.path = path;
        }

        public String text() {
            switch (kind) {
                case ENV: return "${env." + name + "}";
                case SECRET: return "${secret." + name + "}";
                case INPUT: return "${input." + name + "}";
                default: return "${" + name + path + "}";
            }
        }
    }

    /** {@code ${fn.name(args)}} or {@code ${fn.name@version(args)}}; arguments are positional. */
    public static final class Call extends Value {
        public final String function;
        public final String version;
        public final List<Value> args;

        public Call(String function, String version, List<Value> args) {
            this.function = function;
            this.version = version;
            this.args = args;
        }
    }

    /** Text with embedded references, e.g. {@code "Bearer ${login.token}"}. */
    public static final class Interpolation extends Value {
        /** String or Value parts, in order. */
        public final List<Object> parts;
        public Interpolation(List<Object> parts) { this.parts = parts; }
    }

    public static final class ObjectValue extends Value {
        public final Map<String, Value> fields;
        public ObjectValue(Map<String, Value> fields) { this.fields = fields; }
    }

    public static final class ArrayValue extends Value {
        public final List<Value> items;
        public ArrayValue(List<Value> items) { this.items = items; }
    }
}
