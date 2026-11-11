package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.springframework.stereotype.Component;

import java.util.*;

/** A capability is usable only when both its lowering implementation and runtime are known. */
@Component
public class CapabilityRegistry {
    private final Map<String, Capability> capabilities =
            Map.of(
                    "flow.ordered", new Capability("flow.ordered", "1", "ordered-groups-v1", "ApacheJMeter_core:5.6.3",
                            List.of("serial-steps", "serial-calls", "parallel-calls-max-4", "entry-snapshot", "step-barrier")),
                    ExtensionUsage.CAPABILITY, new Capability(ExtensionUsage.CAPABILITY, "1", "jmeter-function-v1", "octopus-jmeter-ext-1",
                            List.of("published-versions-only", "sha256-pinned", "escaped-compound-variables", "request-value", "request-transform")),
                    PollChecks.CAPABILITY, new Capability(PollChecks.CAPABILITY, "1", "while-poll-v1", "ApacheJMeter_core:5.6.3",
                            List.of("repeat-until-oracle", "interval-200ms-30s", "timeout-1s-300s", "max-120-attempts")),
                    "request.prepare", new Capability("request.prepare","1","request-prepare-v1","octopus-jmeter-ext-1",io.yuan.octopus.preparation.PreparationTools.ALL.stream().map(t -> t.id()+"@"+t.version()).toList()),
                    "http",
                            new Capability(
                                    "http",
                                    "1",
                                    "http-json-v1",
                                    "ApacheJMeter_http:5.6.3",
                                    List.of(
                                            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD",
                                            "OPTIONS")),
                    "json",
                            new Capability(
                                    "json",
                                    "1",
                                    "http-json-v1",
                                    "Groovy:bundled-with-JMeter-5.6.3",
                                    List.of(
                                            "object-bindings",
                                            "json-path-fields",
                                            "typed-equality",
                                            "state-observation")),
                    "verify.sql", new Capability("verify.sql", "1", "datastore-v1", "octopus-jmeter-ext-1", List.of("mysql", "postgresql", "parameterized-select", "bounded-poll")),
                    "verify.redis", new Capability("verify.redis", "1", "datastore-v1", "octopus-jmeter-ext-1", List.of("GET", "HGET", "EXISTS", "TTL")),
                    "verify.kafka", new Capability("verify.kafka", "1", "datastore-v1", "octopus-jmeter-ext-1", List.of("new-messages", "key-correlation", "no-offset-commit")),
                    ResponseSchemaChecks.CAPABILITY, new Capability(ResponseSchemaChecks.CAPABILITY, "1", "response-schema-v1", "octopus-jmeter-ext-1",
                            List.of("json-schema-subset", "openapi-nullable", "allOf-anyOf-oneOf-not", "max-50-violations")));

    public List<Capability> list() {
        return capabilities.values().stream().sorted(Comparator.comparing(Capability::id)).toList();
    }

    public List<Capability> require(Collection<String> required) {
        List<String> missing =
                required.stream()
                        .filter(id -> !capabilities.containsKey(id))
                        .distinct()
                        .sorted()
                        .toList();
        if (!missing.isEmpty())
            throw new IllegalArgumentException("Missing capability: " + String.join(", ", missing));
        return required.stream().distinct().sorted().map(capabilities::get).toList();
    }
}
