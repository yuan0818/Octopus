package io.yuan.octopus.verification;

import static io.yuan.octopus.verification.VerificationPlan.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Closed query grammar: identifiers are quoted, values are bound, no executable model text. */
public final class VerificationRules {
    private VerificationRules() {}
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    public static final Set<String> SQL_OPERATORS = Set.of("EQ", "NE", "GT", "GE", "LT", "LE");
    public static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
    public static void validate(VerificationPlan p) {
        require(p != null && Set.of("SQL", "REDIS", "KAFKA").contains(Objects.toString(p.kind(), "")), "请选择 SQL、Redis 或 Kafka 验证");
        var c = p.connection();
        require(c != null && c.repositoryKey() != null && !c.repositoryKey().isBlank() && c.repositoryKey().length() <= 1000
                && c.modulePath() != null && c.modulePath().length() <= 1000 && c.connectionId() != null
                && c.connectionId().matches("[A-Za-z_][A-Za-z0-9_.-]{0,99}"), "请选择项目、模块和已保存连接");
        require(p.description() != null && !p.description().isBlank() && p.description().length() <= 4000, "请说明要验证的业务结果");
        require(p.timeoutMillis() >= 1000 && p.timeoutMillis() <= 30000, "验证等待时间为 1–30 秒");
        require(p.expect() != null && Set.of("EQ", "EXISTS", "GT", "GE").contains(Objects.toString(p.expect().operator(), "")), "请设置预期结果");
        path(p.expect().path());
        if (!"EXISTS".equals(p.expect().operator())) value(p.expect().value());
        switch (p.kind()) {
            case "SQL" -> {
                require(p.redis() == null && p.kafka() == null && p.sql() != null, "SQL 检查只能包含 SQL 查询");
                Sql q = p.sql();
                require(q.table() != null && q.table().split("\\.", -1).length <= 2, "表名格式无效");
                for (String part : q.table().split("\\.", -1)) identifier(part);
                require(q.columns() != null && q.columns().size() <= 30 && (q.count() || !q.columns().isEmpty()), "请选择查询字段或计数");
                q.columns().forEach(VerificationRules::identifier);
                require(new HashSet<>(q.columns()).size() == q.columns().size(), "查询字段不能重复");
                require(q.where() != null && !q.where().isEmpty() && q.where().size() <= 10, "至少指定一个业务数据过滤条件");
                q.where().forEach(f -> { require(f != null, "查询条件不能为空"); identifier(f.column());
                    require(SQL_OPERATORS.contains(Objects.toString(f.operator(), "")), "不支持的 SQL 比较操作"); value(f.value()); });
                require(p.expect().path().equals("$.rowCount") || p.expect().path().startsWith("$.rows["), "SQL 结果字段使用 $.rows[0].字段名 或 $.rowCount");
            }
            case "REDIS" -> {
                require(p.sql() == null && p.kafka() == null && p.redis() != null, "Redis 检查只能包含 Redis 查询");
                Redis q = p.redis();
                require(Set.of("GET", "HGET", "EXISTS", "TTL").contains(Objects.toString(q.command(), "")), "仅支持 GET、HGET、EXISTS、TTL");
                require(q.keyPrefix() != null && q.keyPrefix().length() <= 500, "Redis key 前缀过长"); value(q.key());
                require(!"HGET".equals(q.command()) || q.field() != null && !q.field().isBlank() && q.field().length() <= 200, "请填写 Hash 字段");
                require(p.expect().path().equals("$.value") || p.expect().path().startsWith("$.value."), "Redis 结果字段使用 $.value 或 $.value.字段名");
            }
            case "KAFKA" -> {
                require(p.sql() == null && p.redis() == null && p.kafka() != null, "Kafka 检查只能包含 Kafka 查询");
                require(p.kafka().topic() != null && p.kafka().topic().matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,248}"), "请填写明确的 Kafka topic");
                value(p.kafka().key());
                require(p.expect().path().equals("$.value") || p.expect().path().startsWith("$.value."), "Kafka 检查字段使用 $.value 或 $.value.字段名");
            }
        }
    }
    public static void identifier(String s) { require(s != null && IDENTIFIER.matcher(s).matches(), "表名和字段名仅支持字母、数字、下划线，且以字母或下划线开头"); }
    public static void path(String s) { require(s != null && s.length() <= 500 && s.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]{1,4}\\])*"), "结果字段路径无效"); }
    public static void value(Value v) {
        require(v != null, "请设置测试值或流程变量");
        if ("context".equals(v.source())) require(v.value() instanceof String s && s.matches("[A-Za-z_][A-Za-z0-9_.-]{0,199}"), "流程变量名无效");
        else require("literal".equals(v.source()) && (v.value() instanceof String s && s.length() <= 4000
                || v.value() instanceof Number || v.value() instanceof Boolean), "验证参数只支持普通值或流程变量，不支持脚本和凭据");
    }
    public static List<Value> values(VerificationPlan p) {
        List<Value> values = new ArrayList<>();
        if (p.sql() != null) p.sql().where().forEach(f -> values.add(f.value()));
        if (p.redis() != null) values.add(p.redis().key());
        if (p.kafka() != null) values.add(p.kafka().key());
        if (!"EXISTS".equals(p.expect().operator())) values.add(p.expect().value());
        return values;
    }
    public static Object resolve(Value v, Map<String, ?> context) {
        Object result = "literal".equals(v.source()) ? v.value() : context.get(String.valueOf(v.value()));
        require(result instanceof String || result instanceof Number || result instanceof Boolean, "验证所需的流程变量缺失或类型不正确");
        return result;
    }
    public static String sql(Sql q, String kind) {
        String quote = "mysql".equals(kind) ? "`" : "\"";
        java.util.function.Function<String, String> quoted = s -> quote + s + quote;
        String table = String.join(".", Arrays.stream(q.table().split("\\.")).map(quoted).toList());
        String columns = q.count() ? "COUNT(*) AS " + quoted.apply("count") : String.join(", ", q.columns().stream().map(quoted).toList());
        Map<String,String> operators = Map.of("EQ", "=", "NE", "<>", "GT", ">", "GE", ">=", "LT", "<", "LE", "<=");
        return "SELECT " + columns + " FROM " + table + " WHERE " + String.join(" AND ", q.where().stream()
                .map(f -> quoted.apply(f.column()) + " " + operators.get(f.operator()) + " ?").toList()) + " LIMIT 101";
    }
    public static boolean matches(Expectation expect, Object document, Map<String, ?> context) {
        Object actual = document;
        var matcher = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[([0-9]+)\\]").matcher(expect.path().substring(1));
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                if (!(actual instanceof Map<?,?> m) || !m.containsKey(matcher.group(1))) return false;
                actual = m.get(matcher.group(1));
            } else {
                int i = Integer.parseInt(matcher.group(2));
                if (!(actual instanceof List<?> l) || i >= l.size()) return false;
                actual = l.get(i);
            }
        }
        if ("EXISTS".equals(expect.operator())) return actual != null;
        Object expected = resolve(expect.value(), context);
        if (actual instanceof Number a && expected instanceof Number b) {
            int compare = new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
            return switch (expect.operator()) { case "EQ" -> compare == 0; case "GT" -> compare > 0; case "GE" -> compare >= 0; default -> false; };
        }
        return "EQ".equals(expect.operator()) && Objects.equals(actual, expected);
    }
}
