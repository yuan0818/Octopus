package io.yuan.octopus.verification;

import com.google.gson.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import redis.clients.jedis.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static io.yuan.octopus.verification.VerificationRules.*;

/** Loaded in JMeter's JVM by the compiler's fixed JSR223 templates. Never executes model scripts. */
public final class VerificationRuntime {
    public static final String VERSION = "1";
    private static final Gson JSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    public record ConnectionSettings(String kind, String host, int port, String database, String security,
                                     String username, String password) {
        @Override public String toString() { return "ConnectionSettings[redacted]"; }
    }
    public record Outcome(boolean success, String code, String message) {}
    private VerificationRuntime() {}
    public static VerificationPlan plan(String json) { var p = JSON.fromJson(json, VerificationPlan.class); validate(p); return p; }
    private static ConnectionSettings settings(String file, String key) throws Exception {
        require(file != null, "验证连接未注入");
        var object = JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject();
        require(object.has(key), "验证连接未注入");
        return JSON.fromJson(object.get(key), ConnectionSettings.class);
    }
    public static void validateConnection(ConnectionSettings c, String kind) {
        require(c != null && c.host() != null && !c.host().isBlank() && c.host().matches("[A-Za-z0-9_.:\\-\\[\\]]+")
                && c.port() >= 1 && c.port() <= 65535, "验证连接需要主机和端口");
        require(switch (kind) { case "SQL" -> Set.of("mysql", "postgresql").contains(c.kind()); case "REDIS" -> "redis".equals(c.kind()); case "KAFKA" -> "kafka".equals(c.kind()); default -> false; }, "验证类型和连接类型不匹配");
        String security = security(c);
        require(Set.of("plain", "tls", "sasl_ssl_plain", "sasl_ssl_scram256", "sasl_ssl_scram512").contains(security), "不支持的连接认证方式");
        if (!"KAFKA".equals(kind)) require(Set.of("plain", "tls").contains(security), "此连接只支持普通或 TLS 连接");
        if ("SQL".equals(kind)) require(c.database() != null && c.database().matches("[A-Za-z_][A-Za-z0-9_-]{0,63}"), "数据库名格式无效");
        if ("REDIS".equals(kind)) require(c.database() != null && (c.database().isBlank() || c.database().matches("[0-9]{1,2}") && Integer.parseInt(c.database()) <= 15), "Redis 数据库编号为 0–15");
        if ("KAFKA".equals(kind)) {
            if (security.startsWith("sasl_")) require(c.username() != null && !c.username().isBlank() && c.password() != null && !c.password().isEmpty(), "Kafka SASL 连接需要已保存的用户名和密码");
            else require((c.username() == null || c.username().isBlank()) && (c.password() == null || c.password().isEmpty()), "Kafka 已配置凭据，请选择对应的 SASL TLS 认证方式");
        }
    }
    private static String security(ConnectionSettings c) { return c.security() == null || c.security().isBlank() ? "plain" : c.security(); }
    private static String host(ConnectionSettings c) { return c.host().contains(":") && !c.host().startsWith("[") ? "[" + c.host() + "]" : c.host(); }
    public static Outcome capture(String planJson, String file, String key, Map<String,Object> markers, String markerKey) {
        try {
            var p = plan(planJson); var c = settings(file, key); validateConnection(c, p.kind());
            var captureProperties = kafkaProperties(c);
            captureProperties.setProperty(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");
            try (var consumer = new KafkaConsumer<String,String>(captureProperties)) {
                var partitions = consumer.partitionsFor(p.kafka().topic(), Duration.ofSeconds(5)).stream()
                        .map(i -> new TopicPartition(i.topic(), i.partition())).toList();
                require(!partitions.isEmpty() && partitions.size() <= 100, "Kafka topic 分区不可用或超出范围");
                // Snapshot before the HTTP operation: old messages can never prove a new event.
                markers.put(markerKey, consumer.endOffsets(partitions, Duration.ofSeconds(5)));
            }
            return new Outcome(true, "200", "Kafka observation boundary captured");
        } catch (Exception | LinkageError e) { return error(); }
    }
    public static Outcome verify(String planJson, String file, String key, Map<String,?> context, Map<String,Object> markers, String markerKey) {
        try {
            var p = plan(planJson); var c = settings(file, key); validateConnection(c, p.kind());
            values(p).forEach(v -> resolve(v, context));
            if ("KAFKA".equals(p.kind())) return kafka(p, c, context, markers.remove(markerKey));
            long deadline = System.nanoTime() + Duration.ofMillis(p.timeoutMillis()).toNanos();
            do {
                Object document = "SQL".equals(p.kind()) ? sql(p, c, context) : redis(p, c, context);
                if (matches(p.expect(), document, context)) return pass();
                if (System.nanoTime() >= deadline) return mismatch();
                Thread.sleep(Math.min(250, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
            } while (!Thread.currentThread().isInterrupted());
            return error();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return error(); }
        catch (Exception | LinkageError e) { return error(); }
    }
    static Object sql(VerificationPlan p, ConnectionSettings c, Map<String,?> context) throws Exception {
        Class.forName("mysql".equals(c.kind()) ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
        var props = new Properties();
        props.setProperty("user", Objects.toString(c.username(), "")); props.setProperty("password", Objects.toString(c.password(), ""));
        if ("mysql".equals(c.kind())) {
            props.setProperty("connectTimeout", "5000"); props.setProperty("socketTimeout", "5000");
            props.setProperty("sslMode", "tls".equals(security(c)) ? "VERIFY_IDENTITY" : "DISABLED");
            props.setProperty("allowMultiQueries", "false"); props.setProperty("allowLoadLocalInfile", "false");
        } else {
            props.setProperty("connectTimeout", "5"); props.setProperty("socketTimeout", "5");
            props.setProperty("sslmode", "tls".equals(security(c)) ? "verify-full" : "disable");
        }
        String url = "jdbc:" + c.kind() + "://" + host(c) + ":" + c.port() + "/" + c.database();
        try (var connection = DriverManager.getConnection(url, props)) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try { return query(connection, p, c.kind(), context); }
            finally { connection.rollback(); }
        }
    }
    static Object query(java.sql.Connection connection, VerificationPlan p, String kind, Map<String,?> context) throws Exception {
        try (var statement = connection.prepareStatement(VerificationRules.sql(p.sql(), kind))) {
            statement.setQueryTimeout(5); statement.setMaxRows(101);
            for (int i = 0; i < p.sql().where().size(); i++) statement.setObject(i + 1, resolve(p.sql().where().get(i).value(), context));
            try (var result = statement.executeQuery()) {
                List<Map<String,Object>> rows = new ArrayList<>();
                int chars = 0;
                while (result.next()) {
                    require(rows.size() < 100, "验证结果超过 100 行，请缩小查询范围");
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                        Object value = result.getObject(i);
                        require(value == null || value instanceof Number || value instanceof Boolean || value instanceof String, "查询字段类型不受支持");
                        chars += value == null ? 0 : value.toString().length(); require(chars <= 1_000_000, "验证结果过大");
                        row.put(result.getMetaData().getColumnLabel(i), value);
                    }
                    rows.add(row);
                }
                return Map.of("rows", rows, "rowCount", rows.size());
            }
        }
    }
    static Object redis(VerificationPlan p, ConnectionSettings c, Map<String,?> context) {
        var config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(5000).socketTimeoutMillis(5000)
                .ssl("tls".equals(security(c))).database(c.database().isBlank() ? 0 : Integer.parseInt(c.database()));
        if ("tls".equals(security(c))) {
            var ssl = new javax.net.ssl.SSLParameters(); ssl.setEndpointIdentificationAlgorithm("HTTPS"); config.sslParameters(ssl);
        }
        if (c.username() != null && !c.username().isEmpty()) config.user(c.username());
        if (c.password() != null && !c.password().isEmpty()) config.password(c.password());
        try (var client = new Jedis(new HostAndPort(c.host(), c.port()), config.build())) {
            String key = p.redis().keyPrefix() + resolve(p.redis().key(), context);
            require(!key.isEmpty() && key.length() <= 4096, "Redis key 为空或过长");
            Object value = switch (p.redis().command()) {
                case "GET" -> decode(client.get(key));
                case "HGET" -> decode(client.hget(key, p.redis().field()));
                case "EXISTS" -> client.exists(key);
                case "TTL" -> client.ttl(key);
                default -> throw new IllegalArgumentException("Unsupported Redis command");
            };
            Map<String,Object> result = new HashMap<>(); result.put("value", value); return result;
        }
    }
    static Properties kafkaProperties(ConnectionSettings c) {
        var props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, host(c) + ":" + c.port());
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.setProperty(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        props.setProperty(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.setProperty(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        props.setProperty(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        props.setProperty(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, "1048576");
        props.setProperty(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, "1048576");
        String mode = security(c);
        props.setProperty("security.protocol", mode.startsWith("sasl_") ? "SASL_SSL" : "tls".equals(mode) ? "SSL" : "PLAINTEXT");
        if (mode.startsWith("sasl_")) {
            String mechanism = switch (mode) { case "sasl_ssl_scram256" -> "SCRAM-SHA-256"; case "sasl_ssl_scram512" -> "SCRAM-SHA-512"; default -> "PLAIN"; };
            props.setProperty("sasl.mechanism", mechanism);
            String login = mechanism.equals("PLAIN") ? "org.apache.kafka.common.security.plain.PlainLoginModule" : "org.apache.kafka.common.security.scram.ScramLoginModule";
            props.setProperty("sasl.jaas.config", login + " required username=\"" + escape(c.username()) + "\" password=\"" + escape(c.password()) + "\";");
        }
        // assign(), never subscribe(): no group membership, no offset commits, no consumer-group interference.
        return props;
    }
    private static String escape(String s) { require(s != null && !s.contains("\n") && !s.contains("\r"), "SASL 凭据格式无效"); return s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static KafkaConsumer<String,String> consumer(ConnectionSettings c) { return new KafkaConsumer<>(kafkaProperties(c)); }
    @SuppressWarnings("unchecked")
    private static Outcome kafka(VerificationPlan p, ConnectionSettings c, Map<String,?> context, Object marker) {
        require(marker instanceof Map<?,?> && !((Map<?,?>) marker).isEmpty(), "Kafka 验证缺少操作前的观察边界");
        try (var consumer = consumer(c)) { return observe(consumer, p, context, (Map<TopicPartition,Long>) marker); }
    }
    static Outcome observe(Consumer<String,String> consumer, VerificationPlan p, Map<String,?> context, Map<TopicPartition,Long> offsets) {
        consumer.assign(offsets.keySet()); offsets.forEach(consumer::seek);
        long deadline = System.nanoTime() + Duration.ofMillis(p.timeoutMillis()).toNanos();
        int scanned = 0;
        String key = String.valueOf(resolve(p.kafka().key(), context));
        require(!key.isBlank(), "Kafka 验证必须关联明确的消息 key");
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            var records = consumer.poll(Duration.ofMillis(Math.min(250, Math.max(1, (deadline - System.nanoTime()) / 1_000_000))));
            for (var record : records) {
                require(++scanned <= 1000, "Kafka 观察超过 1000 条消息，无法确定结果");
                if (record.offset() < offsets.getOrDefault(new TopicPartition(record.topic(), record.partition()), Long.MAX_VALUE)
                        || !key.equals(record.key())) continue;
                var document = new HashMap<String,Object>(); document.put("value", decode(record.value()));
                if (matches(p.expect(), document, context)) return pass();
            }
        }
        return mismatch();
    }
    private static Object decode(String value) {
        if (value == null) return null;
        require(value.length() <= 1_000_000, "验证结果过大");
        try { return JSON.fromJson(value, Object.class); } catch (JsonParseException e) { return value; }
    }
    private static Outcome pass() { return new Outcome(true, "200", "Business datastore verification passed"); }
    private static Outcome mismatch() { return new Outcome(false, "VERIFY_MISMATCH", "Expected business state was not observed within the wait window"); }
    private static Outcome error() { return new Outcome(false, "VERIFY_ERROR", "Verification unavailable: check connection, permissions, schema and observation limits"); }
}
