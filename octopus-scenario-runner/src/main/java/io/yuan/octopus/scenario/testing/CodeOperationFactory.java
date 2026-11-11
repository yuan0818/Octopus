package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.IrValidator.require;
import java.util.*;
import java.util.regex.Pattern;

/** Same deterministic reference contract as the visual API picker. */
public final class CodeOperationFactory {
    private CodeOperationFactory() {}
    public static Operation create(RepositoryCatalogClient.Endpoint endpoint, String project, String module) {
        require(endpoint.projectId() > 0 && endpoint.branch() != null && endpoint.commitId() != null
                && endpoint.commitId().matches("[a-fA-F0-9]{40}") && endpoint.signature() != null, "API 缺少固定的分析版本");
        require(Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(endpoint.httpMethod()), "HTTP 方法需明确选择");
        String system = identifier(project), service = identifier(module), signature = endpoint.signature().replaceAll("\\s+", "");
        String route = hash(List.of(endpoint.projectId(), endpoint.httpMethod(), endpoint.path(), signature));
        String version = hash(List.of(endpoint.branch(), endpoint.commitId(), system, service));
        Map<String, Port> inputs = new TreeMap<>();
        var matcher = Pattern.compile("\\{([^}]+)}").matcher(endpoint.path());
        while (matcher.find()) inputs.put("path." + matcher.group(1), new Port("path", matcher.group(1), "string", true, null));
        return new Operation("analysis.p" + endpoint.projectId() + ".api_" + route.substring(0, 24), "code_" + version.substring(0, 32),
                system, service, endpoint.httpMethod(), endpoint.path(), inputs, null, null,
                List.of(new Implementation(endpoint.projectId(), endpoint.branch(), endpoint.commitId(), signature)),
                new Source("ANALYZED_CODE", signature, hash(List.of(route, version))), List.of("http", "json"), null);
    }
    static String identifier(String name) {
        String value = name.replaceAll("[^A-Za-z0-9_.-]", "_"); value = value.substring(0, Math.min(100, value.length()));
        return value.matches("^[A-Za-z_].*") ? value : "project_" + value;
    }
}
