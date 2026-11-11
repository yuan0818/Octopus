package io.yuan.octopus.scenario.dsl;

import io.yuan.octopus.scenario.dsl.DslModel.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static checks that need no catalog and no LLM (FR-PARSE-03): references only to earlier steps, a single
 * producer per name, one status per step, complete negative steps, cleanup rules, function positions and the
 * GIVEN → WHEN → THEN order. Also infers default roles (FR-ROLE-01).
 */
public final class DslChecker {
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}");
    private static final Set<String> RESERVED_ALIASES = Set.of("env", "secret", "input", "fn");

    private DslChecker() {}

    static void check(Document doc) {
        var d = doc.diagnostics;
        var ids = new HashSet<String>();
        var names = new HashSet<String>();
        for (Scenario s : doc.scenarios) {
            if (s.id != null && !ids.add(s.id)) d.add(DslExpressions.error("SCENARIO_ID_DUPLICATE", "场景 id 重复: " + s.id, s.line, 1, null));
            if (s.name != null && !names.add(s.name)) d.add(DslExpressions.error("SCENARIO_NAME_DUPLICATE", "场景名称重复: " + s.name, s.line, 1, "名称用于生成稳定 id"));
            scenario(doc, s, d);
        }
    }

    private static void scenario(Document doc, Scenario s, List<Diagnostic> d) {
        var steps = new ArrayList<Step>(doc.background);
        steps.addAll(s.steps);
        var defined = new LinkedHashMap<String, Integer>();
        var all = new HashMap<String, Integer>();
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).alias != null) all.putIfAbsent(steps.get(i).alias, i);
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            for (Value v : values(step)) refs(v, defined.keySet(), all.keySet(), s, d);
            for (String f : step.from)
                if (!defined.containsKey(f))
                    d.add(DslExpressions.error(all.containsKey(f) ? "REF_NOT_UPSTREAM" : "REF_UNDEFINED",
                            "from 只能引用前面步骤的 as 名称: " + f, step.line, step.column, null));
            positions(step, d);
            statuses(step, d);
            Integer status = status(step);
            if (status != null && status >= 400) {
                if (step.expects.stream().noneMatch(e -> e.kind == Expect.Kind.JSON_EQUALS || e.kind == Expect.Kind.JSON_EXISTS))
                    d.add(DslExpressions.error("NEGATIVE_INCOMPLETE", "负向步骤除状态码外至少需要一个业务断言，例如 $.code == \"AMOUNT_INVALID\"",
                            step.line, step.column, "FR-LANG-23"));
                if (i != steps.size() - 1)
                    d.add(DslExpressions.error("NEGATIVE_NOT_LAST", "负向步骤必须是场景的最后一个步骤（预期被拒绝后不再继续）", step.line, step.column,
                            "需要后续步骤时，请把负向检查写成单独的场景"));
                if (step.alias != null)
                    d.add(new Diagnostic("WARNING", "NEGATIVE_ALIAS", "负向步骤的输出不会被保存，as 将被忽略", step.line, step.column, null));
            }
            if (step.poll != null && step.poll.everyMillis != null && step.poll.withinMillis < step.poll.everyMillis)
                d.add(DslExpressions.error("POLL", "poll.within 不能小于 every", step.poll.line, 1, null));
            if (step.alias != null) {
                if (RESERVED_ALIASES.contains(step.alias))
                    d.add(DslExpressions.error("ALIAS_RESERVED", "as 不能使用保留名称 " + step.alias, step.line, step.column, null));
                else if (defined.containsKey(step.alias))
                    d.add(DslExpressions.error("ALIAS_DUPLICATE", "as 名称重复（每个上下文值只能有一个生产者）: " + step.alias, step.line, step.column, null));
                defined.putIfAbsent(step.alias, i);
            }
        }
        for (Step c : s.cleanup) {
            var refs = new ArrayList<Ref>();
            for (Value v : values(c)) collect(v, refs);
            if (refs.stream().noneMatch(r -> r.kind == Ref.Kind.STEP))
                d.add(DslExpressions.error("CLEANUP_RESOURCE", "清理调用必须引用本场景产生的资源，例如 ${order.id}", c.line, c.column, null));
            for (Value v : values(c)) refs(v, defined.keySet(), all.keySet(), s, d);
            if (c.alias != null || c.poll != null || c.waitMillis > 0 || !c.from.isEmpty())
                d.add(DslExpressions.error("CLEANUP_SYNTAX", "清理调用不能声明 as、from、wait 或 poll", c.line, c.column, null));
            if (c.expects.stream().anyMatch(e -> e.kind != Expect.Kind.STATUS))
                d.add(DslExpressions.error("CLEANUP_SYNTAX", "清理调用只能声明预期状态码", c.line, c.column, null));
            positions(c, d);
            statuses(c, d);
        }
        roles(steps, d);
    }

    /** At most one expected status: {@code status:} and {@code expect: status N} are the same oracle. */
    private static void statuses(Step step, List<Diagnostic> d) {
        long n = step.expects.stream().filter(e -> e.kind == Expect.Kind.STATUS).count() + (step.status == null ? 0 : 1);
        if (n > 1) d.add(DslExpressions.error("STATUS_DUPLICATE", "每个步骤只能声明一个预期状态码", step.line, step.column, null));
    }

    public static Integer status(Step step) {
        if (step.status != null) return step.status;
        for (Expect e : step.expects) if (e.kind == Expect.Kind.STATUS) return ((Long) e.expected).intValue();
        return null;
    }

    /** I1: functions and interpolation become preparation results, which can feed headers and top-level body fields only. */
    private static void positions(Step step, List<Diagnostic> d) {
        var pathParams = pathParams(step.path);
        for (var e : step.with.entrySet()) {
            boolean dynamic = dynamic(e.getValue());
            if (dynamic && pathParams.contains(e.getKey()))
                d.add(DslExpressions.error("FN_POSITION", "路径参数 " + e.getKey() + " 不能使用函数或文本拼接（只能是字面量或引用）", e.getValue().line, e.getValue().column, null));
            if (nestedDynamic(e.getValue()))
                d.add(DslExpressions.error("FN_POSITION", "函数或文本拼接只能用于请求头和请求体顶层字段", e.getValue().line, e.getValue().column, null));
        }
        for (var e : step.query.entrySet())
            if (dynamic(e.getValue()) || nestedDynamic(e.getValue()))
                d.add(DslExpressions.error("FN_POSITION", "查询参数 " + e.getKey() + " 不能使用函数或文本拼接", e.getValue().line, e.getValue().column, null));
        if (step.body != null && !(step.body instanceof ObjectValue) && dynamic(step.body))
            d.add(DslExpressions.error("FN_POSITION", "body 为函数结果时请使用对象形式逐字段声明", step.body.line, step.body.column, null));
        if (step.body instanceof ObjectValue)
            for (Value v : ((ObjectValue) step.body).fields.values())
                if (nestedDynamic(v)) d.add(DslExpressions.error("FN_POSITION", "函数或文本拼接只能用于请求体顶层字段", v.line, v.column, null));
    }

    static boolean dynamic(Value v) {
        return v instanceof Call || v instanceof Interpolation;
    }

    private static boolean nestedDynamic(Value v) {
        if (v instanceof ObjectValue) {
            for (Value c : ((ObjectValue) v).fields.values()) if (dynamic(c) || nestedDynamic(c)) return true;
        } else if (v instanceof ArrayValue) {
            for (Value c : ((ArrayValue) v).items) if (dynamic(c) || nestedDynamic(c)) return true;
        }
        return false;
    }

    public static Set<String> pathParams(String path) {
        var result = new LinkedHashSet<String>();
        Matcher m = PATH_PARAM.matcher(path == null ? "" : path);
        while (m.find()) result.add(m.group(1));
        return result;
    }

    private static void refs(Value v, Set<String> upstream, Set<String> all, Scenario s, List<Diagnostic> d) {
        var refs = new ArrayList<Ref>();
        collect(v, refs);
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.STEP && !upstream.contains(r.name))
                d.add(DslExpressions.error(all.contains(r.name) ? "REF_NOT_UPSTREAM" : "REF_UNDEFINED",
                        (all.contains(r.name) ? "只能引用前面步骤的结果: " : "引用了未定义的步骤结果: ") + r.text(), r.line, r.column,
                        all.contains(r.name) ? "调整步骤顺序，或改为引用更早的步骤" : "请在产生该值的步骤上声明 as: " + r.name));
            if (r.kind == Ref.Kind.INPUT && !s.inputs.containsKey(r.name))
                d.add(DslExpressions.error("INPUT_UNDECLARED", "场景输入未声明: " + r.text(), r.line, r.column, "在场景的 inputs 中声明 " + r.name + ": string"));
        }
    }

    public static void collect(Value v, List<Ref> out) {
        if (v instanceof Ref) out.add((Ref) v);
        else if (v instanceof Call) ((Call) v).args.forEach(a -> collect(a, out));
        else if (v instanceof Interpolation) {
            for (Object p : ((Interpolation) v).parts) if (p instanceof Value) collect((Value) p, out);
        } else if (v instanceof ObjectValue) ((ObjectValue) v).fields.values().forEach(c -> collect(c, out));
        else if (v instanceof ArrayValue) ((ArrayValue) v).items.forEach(c -> collect(c, out));
    }

    public static List<Value> values(Step step) {
        var result = new ArrayList<Value>(step.with.values());
        result.addAll(step.query.values());
        result.addAll(step.headers.values());
        if (step.body != null) result.add(step.body);
        return result;
    }

    /**
     * FR-ROLE-01 default roles: steps before the first one with an explicit expectation are GIVEN; from there on,
     * steps are WHEN, except read-only steps (GET/HEAD) after the last non-read-only step, which are THEN. Without
     * any expectation the last step is WHEN. Explicit {@code role} overrides apply; the order must stay
     * GIVEN* → WHEN+ → THEN*.
     */
    public static List<String> roles(List<Step> steps, List<Diagnostic> d) {
        var roles = new ArrayList<String>();
        int firstExpect = -1;
        for (int i = 0; i < steps.size(); i++) if (!steps.get(i).expects.isEmpty() || steps.get(i).status != null) { firstExpect = i; break; }
        if (firstExpect < 0) firstExpect = steps.size() - 1;
        int lastWrite = -1;
        for (int i = firstExpect; i < steps.size(); i++) if (!readOnly(steps.get(i))) lastWrite = i;
        if (lastWrite < 0) lastWrite = firstExpect;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String role = i < firstExpect ? "GIVEN" : i > lastWrite && readOnly(s) ? "THEN" : "WHEN";
            roles.add(s.role != null ? s.role : role);
        }
        if (d != null) {
            int rank = 0;
            boolean when = false;
            for (int i = 0; i < steps.size(); i++) {
                int r = roles.get(i).equals("GIVEN") ? 0 : roles.get(i).equals("WHEN") ? 1 : 2;
                if (r < rank) d.add(DslExpressions.error("ROLE_ORDER", "角色顺序必须是 GIVEN → WHEN → THEN", steps.get(i).line, steps.get(i).column, null));
                rank = Math.max(rank, r);
                when |= r == 1;
                if (r == 2 && steps.get(i).method != null && !readOnly(steps.get(i)))
                    d.add(DslExpressions.error("THEN_NOT_READ_ONLY", "THEN 步骤只能是只读请求（GET / HEAD）", steps.get(i).line, steps.get(i).column, "会改变状态的动作属于 WHEN"));
            }
            if (!when && !steps.isEmpty()) d.add(DslExpressions.error("ROLE_NO_WHEN", "场景至少需要一个 WHEN 步骤", steps.get(0).line, 1, null));
        }
        return roles;
    }

    private static boolean readOnly(Step s) {
        return "GET".equals(s.method) || "HEAD".equals(s.method);
    }
}
