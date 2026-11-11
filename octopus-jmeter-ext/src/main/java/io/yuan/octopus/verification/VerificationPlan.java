package io.yuan.octopus.verification;

import java.util.List;

/** Reviewed business oracle, independent of JMeter, driver classes, environments and credentials. */
public record VerificationPlan(String kind, Source connection, String description, Sql sql, Redis redis,
                               Kafka kafka, Expectation expect, int timeoutMillis) {
    public record Source(String repositoryKey, String modulePath, String connectionId) {}
    public record Value(String source, Object value) {}
    public record Filter(String column, String operator, Value value) {}
    public record Sql(String table, List<String> columns, boolean count, List<Filter> where) {}
    public record Redis(String command, String keyPrefix, Value key, String field) {}
    public record Kafka(String topic, Value key) {}
    public record Expectation(String path, String operator, Value value) {}
}
