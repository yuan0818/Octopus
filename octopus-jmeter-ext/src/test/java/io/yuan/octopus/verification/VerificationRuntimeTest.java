package io.yuan.octopus.verification;

import static io.yuan.octopus.verification.VerificationPlan.*;
import static io.yuan.octopus.verification.VerificationRules.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.*;
import java.sql.DriverManager;
import java.util.*;

class VerificationRuntimeTest {
    static Source source = new Source("repository", "module", "orders");
    static Value ref(String key) { return new Value("context", key); }
    static Value lit(Object value) { return new Value("literal", value); }
    static VerificationPlan sqlPlan(String table) {
        return new VerificationPlan("SQL", source, "本次订单已支付", new Sql(table, List.of("status"), false,
                List.of(new Filter("id", "EQ", ref("order.id")))), null, null,
                new Expectation("$.rows[0].status", "EQ", lit("PAID")), 1000);
    }
    @Test void onlyClosedReadQueriesAndScalarBindingsAreAccepted() {
        validate(sqlPlan("orders"));
        for (String table : List.of("orders; DROP TABLE orders", "orders UNION SELECT", "orders--", "orders()"))
            assertThrows(IllegalArgumentException.class, () -> validate(sqlPlan(table)));
        assertThrows(IllegalArgumentException.class, () -> value(new Value("script", "anything")));
        assertThrows(IllegalArgumentException.class, () -> value(new Value("secret", "password")));
        assertThrows(IllegalArgumentException.class, () -> resolve(ref("missing"), Map.of()));
    }
    @Test void preparedStatementKeepsInjectionAsDataAndQueriesRealRows() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:verify;DATABASE_TO_LOWER=TRUE")) {
            c.createStatement().execute("CREATE TABLE orders(id VARCHAR, status VARCHAR)");
            c.createStatement().execute("INSERT INTO orders VALUES('one','PAID'),('two','PENDING')");
            var p = sqlPlan("orders");
            Object good = VerificationRuntime.query(c, p, "postgresql", Map.of("order.id", "one"));
            assertTrue(matches(p.expect(), good, Map.of()));
            Object injected = VerificationRuntime.query(c, p, "postgresql", Map.of("order.id", "one' OR '1'='1"));
            assertFalse(matches(p.expect(), injected, Map.of()));
            assertFalse(matches(p.expect(), VerificationRuntime.query(c, p, "postgresql", Map.of("order.id", "two")), Map.of()));
        }
    }
    @Test void oversizedSqlResultCannotProvePartialSuccess() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:limit;DATABASE_TO_LOWER=TRUE")) {
            c.createStatement().execute("CREATE TABLE orders(id VARCHAR, status VARCHAR)");
            c.createStatement().execute("INSERT INTO orders SELECT 'one','PAID' FROM SYSTEM_RANGE(1,101)");
            assertThrows(IllegalArgumentException.class, () -> VerificationRuntime.query(c, sqlPlan("orders"), "postgresql", Map.of("order.id", "one")));
        }
    }
    @Test void kafkaIgnoresHistoricalAndUnrelatedRecordsAndNeverCommits() {
        var tp = new TopicPartition("orders", 0);
        var p = new VerificationPlan("KAFKA", source, "产生支付事件", null, null, new Kafka("orders", ref("order.id")),
                new Expectation("$.value.status", "EQ", lit("PAID")), 1000);
        try (var consumer = new MockConsumer<String,String>(OffsetResetStrategy.NONE)) {
            consumer.updateBeginningOffsets(Map.of(tp, 0L)); consumer.updateEndOffsets(Map.of(tp, 4L));
            consumer.schedulePollTask(() -> {
                consumer.addRecord(new ConsumerRecord<>("orders", 0, 0L, "one", "{\"status\":\"PAID\"}"));
                consumer.addRecord(new ConsumerRecord<>("orders", 0, 1L, "other", "{\"status\":\"PAID\"}"));
                consumer.addRecord(new ConsumerRecord<>("orders", 0, 2L, "one", "{\"status\":\"PENDING\"}"));
                consumer.addRecord(new ConsumerRecord<>("orders", 0, 3L, "one", "{\"status\":\"PAID\"}"));
            });
            assertTrue(VerificationRuntime.observe(consumer, p, Map.of("order.id", "one"), Map.of(tp, 1L)).success());
            assertNull(consumer.committed(Set.of(tp)).get(tp));
            assertTrue(consumer.subscription().isEmpty());
        }
    }
    @Test void kafkaDoesNotPassFromOldOrDifferentBusinessEntity() {
        var tp = new TopicPartition("orders", 0);
        var p = new VerificationPlan("KAFKA", source, "产生支付事件", null, null, new Kafka("orders", ref("order.id")),
                new Expectation("$.value.status", "EQ", lit("PAID")), 1000);
        try (var consumer = new MockConsumer<String,String>(OffsetResetStrategy.NONE)) {
            consumer.updateBeginningOffsets(Map.of(tp, 0L)); consumer.updateEndOffsets(Map.of(tp, 2L));
            consumer.schedulePollTask(() -> {
                consumer.addRecord(new ConsumerRecord<>("orders", 0, 0L, "one", "{\"status\":\"PAID\"}"));
                consumer.addRecord(new ConsumerRecord<>("orders", 0, 1L, "other", "{\"status\":\"PAID\"}"));
            });
            assertEquals("VERIFY_MISMATCH", VerificationRuntime.observe(consumer, p, Map.of("order.id", "one"), Map.of(tp, 1L)).code());
        }
    }
    @Test void kafkaCredentialsRequireExplicitTlsAuthenticationAndCannotInjectJaasOptions() {
        var c = new VerificationRuntime.ConnectionSettings("kafka", "localhost", 9092, "", "sasl_ssl_plain", "user\"", "password\\\"");
        VerificationRuntime.validateConnection(c, "KAFKA");
        var props = VerificationRuntime.kafkaProperties(c);
        assertEquals("false", props.getProperty("enable.auto.commit"));
        assertEquals("false", props.getProperty("allow.auto.create.topics"));
        assertNull(props.getProperty("group.id"));
        assertEquals("SASL_SSL", props.getProperty("security.protocol"));
        assertFalse(c.toString().contains("password"));
        assertThrows(IllegalArgumentException.class, () -> VerificationRuntime.validateConnection(
                new VerificationRuntime.ConnectionSettings("kafka", "localhost", 9092, "", "plain", "user", "password"), "KAFKA"));
    }
    @Test void pathsAreStrictAndMissingValuesAreNotSuccess() {
        assertTrue(matches(new Expectation("$.value", "EQ", lit(1)), Map.of("value", 1L), Map.of()));
        assertFalse(matches(new Expectation("$.value.status", "EXISTS", null), Map.of("value", Map.of()), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> path("$.value[?(@.id)]"));
    }
}
