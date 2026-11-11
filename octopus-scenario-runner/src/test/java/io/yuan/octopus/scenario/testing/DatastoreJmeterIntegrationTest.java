package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static org.junit.jupiter.api.Assertions.*;
import io.yuan.octopus.verification.*;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import redis.clients.jedis.Jedis;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, disposable fixtures only: loopback MySQL 23306, Redis 26379 and Kafka 29092. */
@EnabledIfEnvironmentVariable(named="OCTOPUS_VERIFY_FIXTURES", matches="true")
class DatastoreJmeterIntegrationTest {
    @TempDir Path directory;
    HttpServer http;
    String order = "order_" + UUID.randomUUID().toString().replace("-", "");
    String topic = "verify_" + UUID.randomUUID().toString().replace("-", "");
    boolean publish = true;
    final String user = "verification_reader", password = "fixture_only_quote\"slash\\secret";
    @BeforeEach void setup() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:mysql://127.0.0.1:23306/verification?sslMode=DISABLED&allowPublicKeyRetrieval=true", "root", "")) {
            db.createStatement().execute("CREATE TABLE IF NOT EXISTS orders(id VARCHAR(100) PRIMARY KEY, status VARCHAR(20))");
            db.createStatement().execute("CREATE USER IF NOT EXISTS 'verification_reader'@'%' IDENTIFIED BY 'fixture_only_quote\"slash\\\\secret'");
            db.createStatement().execute("GRANT SELECT ON verification.* TO 'verification_reader'@'%'");
            try (var s = db.prepareStatement("INSERT INTO orders VALUES(?, 'PENDING')")) { s.setString(1, order); s.executeUpdate(); }
        }
        // Warm the disposable caching_sha2 account using the fixture's explicitly trusted loopback server.
        try (var ignored = DriverManager.getConnection("jdbc:mysql://127.0.0.1:23306/verification?sslMode=DISABLED&allowPublicKeyRetrieval=true", user, password)) { }
        try (var admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:29092"))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short)1))).all().get(10, TimeUnit.SECONDS);
        }
        // A historical matching message must not count as this request's event.
        publish();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/pay", exchange -> {
            try {
                try (var db = DriverManager.getConnection("jdbc:mysql://127.0.0.1:23306/verification?sslMode=DISABLED&allowPublicKeyRetrieval=true", "root", ""); var s = db.prepareStatement("UPDATE orders SET status='PAID' WHERE id=?")) { s.setString(1, order); s.executeUpdate(); }
                try (var redis = new Jedis("127.0.0.1", 26379)) { redis.set("order:" + order, "{\"status\":\"PAID\"}"); }
                if (publish) publish();
                byte[] data = GSON.toJson(Map.of("orderId", order)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data);
            } catch (Exception e) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); http.start();
    }
    void publish() throws Exception {
        var props = new Properties(); props.setProperty("bootstrap.servers", "127.0.0.1:29092");
        props.setProperty("key.serializer", StringSerializer.class.getName()); props.setProperty("value.serializer", StringSerializer.class.getName());
        try (var producer = new KafkaProducer<String,String>(props)) { producer.send(new ProducerRecord<>(topic, order, "{\"status\":\"PAID\"}")).get(10, TimeUnit.SECONDS); }
    }
    @AfterEach void stop() throws Exception {
        if (http != null) http.stop(0);
        try (var admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:29092"))) { admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS); }
        try (var redis = new Jedis("127.0.0.1", 26379)) { redis.del("order:" + order); }
        try (var db = DriverManager.getConnection("jdbc:mysql://127.0.0.1:23306/verification?sslMode=DISABLED&allowPublicKeyRetrieval=true", "root", ""); var s = db.prepareStatement("DELETE FROM orders WHERE id=?")) { s.setString(1, order); s.executeUpdate(); }
    }
    JmeterProcessExecutor.Result execute(String expected, boolean wrongPassword, String... kinds) throws Exception {
        var op = new Operation("orders.pay", "v1", "shop", "orders", "POST", "/pay", Map.of(), Map.of(), Map.of(), List.of(), new Source("MANUAL", "fixture", "fixture"), List.of("http", "json"), null);
        var checks = new ArrayList<Assertion>(); checks.add(new Assertion("status", "STATUS_EQUALS", null, 200));
        Map<String,VerificationRuntime.ConnectionSettings> connections = new HashMap<>();
        List<String> caps = new ArrayList<>(List.of("http", "json"));
        for (String kind : kinds) {
            var source = new VerificationPlan.Source("fixture", "", kind);
            var key = new VerificationPlan.Value("context", "order.id");
            var p = new VerificationPlan(kind, source, "支付后的业务结果", kind.equals("SQL") ? new VerificationPlan.Sql("orders", List.of("status"), false, List.of(new VerificationPlan.Filter("id", "EQ", key))) : null,
                    kind.equals("REDIS") ? new VerificationPlan.Redis("GET", "order:", key, null) : null,
                    kind.equals("KAFKA") ? new VerificationPlan.Kafka(topic, key) : null,
                    new VerificationPlan.Expectation(kind.equals("SQL") ? "$.rows[0].status" : "$.value.status", "EQ", new VerificationPlan.Value("literal", expected)), 1500);
            checks.add(new Assertion(kind.toLowerCase(), DatastoreChecks.KIND, null, p)); caps.add("verify." + kind.toLowerCase());
            var settings = switch(kind) {
                case "SQL" -> new VerificationRuntime.ConnectionSettings("mysql", "127.0.0.1", 23306, "verification", "plain", user, wrongPassword ? "wrong" : password);
                case "REDIS" -> new VerificationRuntime.ConnectionSettings("redis", "127.0.0.1", 26379, "0", "plain", "", "");
                default -> new VerificationRuntime.ConnectionSettings("kafka", "127.0.0.1", 29092, "", "plain", "", "");
            };
            connections.put(DatastoreChecks.key(source), settings);
        }
        var ir = new TestIR("0.1", "pay.test", "pay", 1, "hash", "normal", "oracle", Map.of(), List.of(op), List.of(),
                List.of(new Step("pay", op.ref(), Map.of(), List.of(new Output("order.id", "$.orderId", "string", false)), List.of(), checks, 0)), List.of(), caps, null, List.of());
        var profile = new ExecutionProfile(Map.of("shop.orders", new Target("http://127.0.0.1:" + http.getAddress().getPort())), Map.of(), Map.of(), "test");
        var registry = new CapabilityRegistry(); var compiled = new JmxCompiler(new IrValidator(registry), registry).compile(ir, profile);
        var result = new JmeterProcessExecutor().execute(Path.of(System.getenv("JMETER_HOME")), directory, compiled, profile, 45, new AtomicBoolean(), connections);
        assertFalse(Files.exists(directory.resolve("connections.json"))); assertFalse(Files.exists(directory.resolve("context.json")));
        try (var files = Files.walk(directory)) { for (var file : files.filter(Files::isRegularFile).toList()) assertFalse(Files.readString(file).contains(password), "Credential escaped into " + file.getFileName()); }
        // Safe diagnostics contain only codes/labels, never protocol exceptions or returned data.
        System.out.println(canonical(result));
        return result;
    }
    @Test void jmeterExecutesSqlRedisKafkaAfterHttpWithSameStepOutput() throws Exception {
        var result = execute("PAID", false, "SQL", "REDIS", "KAFKA");
        assertEquals("PASS", result.verdict());
        assertEquals(List.of("guard:pay", "observe:pay:kafka", "step:pay", "verify:pay:sql", "verify:pay:redis", "verify:pay:kafka"), result.samples().stream().map(JmeterProcessExecutor.Sample::label).toList());
    }
    @Test void historicalKafkaMessageCannotPassWhenThisCallPublishesNothing() throws Exception {
        publish = false; var result = execute("PAID", false, "KAFKA");
        assertEquals("FAIL", result.verdict()); assertEquals("VERIFY_MISMATCH", result.samples().getLast().responseCode());
    }
    @Test void incorrectBusinessExpectationFailsAndStopsLaterChecks() throws Exception {
        var result = execute("CANCELLED", false, "REDIS", "SQL");
        assertEquals("FAIL", result.verdict()); assertEquals("verify:pay:redis", result.samples().getLast().label());
    }
    @Test void connectionFailureIsInconclusiveRatherThanBusinessSuccess() throws Exception {
        var result = execute("PAID", true, "SQL");
        assertEquals("INCONCLUSIVE", result.verdict()); assertEquals("VERIFY_ERROR", result.samples().getLast().responseCode());
    }
}
