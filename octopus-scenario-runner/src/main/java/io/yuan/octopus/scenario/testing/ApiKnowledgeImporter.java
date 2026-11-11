package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import com.google.gson.*;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Imports OpenAPI 3 JSON without fetching remote references or inventing undocumented contracts.
 */
@Component
public class ApiKnowledgeImporter {
    private final IrValidator validator;

    public ApiKnowledgeImporter(IrValidator validator) {
        this.validator = validator;
    }

    public record ImportRequest(
            String system,
            String service,
            Map<String, Object> document,
            Map<String, List<Implementation>> implementations) {}

    public List<Operation> openApi(ImportRequest request) {
        IrValidator.identifier(request.system(), "system");
        IrValidator.identifier(request.service(), "service");
        JsonObject root = GSON.toJsonTree(request.document()).getAsJsonObject();
        require(string(root, "openapi", "").matches("3\\.[01]\\.[0-9]+"), "Only the documented OpenAPI 3.0/3.1 JSON subset is supported");
        JsonObject paths = object(root, "paths");
        List<Operation> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String path : new TreeSet<>(paths.keySet())) {
            JsonObject item = resolve(root, paths.get(path), new HashSet<>()).getAsJsonObject();
            for (String method :
                    List.of("get", "post", "put", "patch", "delete", "head", "options")) {
                if (!item.has(method)) continue;
                JsonObject raw = item.getAsJsonObject(method);
                String sourceId =
                        string(raw, "operationId", method + "." + hash(path).substring(0, 12));
                String id =
                        request.system()
                                + "."
                                + request.service()
                                + "."
                                + (sourceId.matches("[A-Za-z_][A-Za-z0-9_.-]{0,60}")
                                        ? sourceId
                                        : "operation_" + hash(sourceId).substring(0, 20));
                require(ids.add(id), "Duplicate OpenAPI operationId: " + sourceId);
                Map<String, Port> ports = new TreeMap<>();
                Map<String, Object> parameterSchemas = new TreeMap<>();
                Map<String, JsonObject> parameters = new LinkedHashMap<>();
                for (JsonElement owner : List.of(item, raw))
                    for (JsonElement p : array(owner.getAsJsonObject(), "parameters")) {
                        JsonObject parameter = resolve(root, p, new HashSet<>()).getAsJsonObject();
                        parameters.put(
                                string(parameter, "in", "") + ":" + string(parameter, "name", ""),
                                parameter);
                    }
                for (var parameter : parameters.values()) {
                    String location = string(parameter, "in", "");
                    String name = string(parameter, "name", "");
                    require(
                            Set.of("path", "query", "header").contains(location),
                            "Unsupported parameter location: " + location);
                    require(
                            !parameter.has("style")
                                    || "simple".equals(string(parameter, "style", ""))
                                    || "form".equals(string(parameter, "style", "")),
                            "Unsupported parameter serialization");
                    String type = type(object(parameter, "schema"));
                    require(
                            !Set.of("object", "array").contains(type),
                            "Structured path/query/header parameters need a serialization"
                                    + " capability");
                    parameterSchemas.put(location + "." + name, map(object(parameter, "schema")));
                    ports.put(
                            location + "." + name,
                            new Port(
                                    location,
                                    name,
                                    type,
                                    parameter.has("required")
                                            && parameter.get("required").getAsBoolean(),
                                    string(parameter,"x-octopus-prefix",null)));
                }
                Map<String, Object> requestSchema = Map.of("type", "object");
                if (raw.has("requestBody")) {
                    JsonObject body =
                            resolve(root, raw.get("requestBody"), new HashSet<>())
                                    .getAsJsonObject();
                    JsonObject content = object(body, "content");
                    require(
                            content.has("application/json"),
                            "Request needs unsupported serializer (only application/json is"
                                    + " enabled)");
                    JsonObject schema =
                            object(content.getAsJsonObject("application/json"), "schema");
                    requestSchema = map(schema);
                    ports.put(
                            "body",
                            new Port(
                                    "body",
                                    "$",
                                    type(schema),
                                    body.has("required") && body.get("required").getAsBoolean(),
                                    null));
                }
                if (!parameterSchemas.isEmpty()) {
                    requestSchema = new TreeMap<>(requestSchema);
                    requestSchema.put("x-octopus-parameters", parameterSchemas);
                }
                Map<String, Object> responses = new TreeMap<>();
                for (var response : object(raw, "responses").entrySet()) {
                    JsonObject resolved =
                            resolve(root, response.getValue(), new HashSet<>()).getAsJsonObject();
                    JsonObject content = object(resolved, "content");
                    if (content.has("application/json"))
                        responses.put(
                                response.getKey(),
                                map(object(content.getAsJsonObject("application/json"), "schema")));
                    else if (content.size() == 0)
                        responses.put(response.getKey(), Map.of("type", "null"));
                    else
                        throw new IllegalArgumentException(
                                "Response needs unsupported serializer: " + sourceId);
                }
                JsonArray security =
                        raw.has("security") ? array(raw, "security") : array(root, "security");
                require(
                        security.size() <= 1,
                        "Alternative authentication schemes require explicit normalization");
                if (!security.isEmpty())
                    for (String name : security.get(0).getAsJsonObject().keySet()) {
                        JsonObject scheme =
                                object(object(root, "components"), "securitySchemes")
                                        .getAsJsonObject(name);
                        require(scheme != null, "Unknown security scheme: " + name);
                        if ("http".equals(string(scheme, "type", ""))
                                && "bearer".equalsIgnoreCase(string(scheme, "scheme", ""))) {
                            ports.put(
                                    "auth." + name,
                                    new Port("header", "Authorization", "string", true, "Bearer "));
                        } else if ("apiKey".equals(string(scheme, "type", ""))) {
                            String location = string(scheme, "in", "");
                            require(
                                    Set.of("header", "query").contains(location),
                                    "Unsupported API key placement");
                            ports.put(
                                    "auth." + name,
                                    new Port(
                                            location,
                                            string(scheme, "name", ""),
                                            "string",
                                            true,
                                            null));
                        } else
                            throw new IllegalArgumentException(
                                    "Missing authentication capability: " + name);
                    }
                Source source =
                        new Source(
                                "OPENAPI", method.toUpperCase(Locale.ROOT) + " " + path, hash(raw));
                List<Implementation> implementations =
                        request.implementations() == null
                                ? List.of()
                                : request.implementations().getOrDefault(sourceId, List.of());
                Operation draft =
                        new Operation(
                                id,
                                "draft",
                                request.system(),
                                request.service(),
                                method.toUpperCase(Locale.ROOT),
                                path,
                                ports,
                                requestSchema,
                                responses,
                                List.of(),
                                source,
                                List.of("http", "json"),
                                null);
                Operation operation =
                        new Operation(
                                id,
                                hash(draft).substring(0, 20),
                                draft.system(),
                                draft.service(),
                                draft.method(),
                                draft.path(),
                                ports,
                                requestSchema,
                                responses,
                                implementations,
                                source,
                                draft.requiresCapabilities(),
                                null);
                validator.validateOperation(operation);
                result.add(operation);
            }
        }
        require(!result.isEmpty(), "OpenAPI contains no supported operations");
        return result;
    }

    public Operation endpoint(String system, String service, Map<String, Object> endpoint) {
        String method = String.valueOf(endpoint.get("httpMethod"));
        String path = String.valueOf(endpoint.get("path"));
        Map<String, Port> inputs = new TreeMap<>();
        var matcher = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(path);
        while (matcher.find())
            inputs.put(
                    "path." + matcher.group(1),
                    new Port("path", matcher.group(1), "string", true, null));
        String id = system + "." + service + ".route_" + hash(method + ":" + path).substring(0, 16);
        Implementation impl =
                new Implementation(
                        ((Number) endpoint.get("projectId")).longValue(),
                        String.valueOf(endpoint.get("branch")),
                        String.valueOf(endpoint.get("commitId")),
                        String.valueOf(endpoint.get("signature")));
        Operation operation =
                new Operation(
                        id,
                        "discovered",
                        system,
                        service,
                        method,
                        path,
                        inputs,
                        null,
                        null,
                        List.of(impl),
                        new Source("ENDPOINT", method + " " + path, hash(method + ":" + path)),
                        List.of("http", "json"),
                        null);
        validator.validateOperation(operation);
        return operation;
    }

    private JsonElement resolve(JsonObject root, JsonElement element, Set<String> stack) {
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            element.getAsJsonArray().forEach(e -> out.add(resolve(root, e, new HashSet<>(stack))));
            return out;
        }
        if (!element.isJsonObject()) return element.deepCopy();
        JsonObject object = element.getAsJsonObject();
        if (object.has("$ref")) {
            String ref = object.get("$ref").getAsString();
            require(
                    ref.startsWith("#/") && stack.add(ref) && stack.size() <= 30,
                    "Remote or recursive references require explicit normalization: " + ref);
            JsonElement resolved = root;
            for (String segment : ref.substring(2).split("/")) {
                require(
                        resolved != null && resolved.isJsonObject(),
                        "Unresolved reference: " + ref);
                resolved =
                        resolved.getAsJsonObject()
                                .get(segment.replace("~1", "/").replace("~0", "~"));
            }
            require(resolved != null, "Unresolved reference: " + ref);
            return resolve(root, resolved, stack);
        }
        JsonObject out = new JsonObject();
        object.entrySet()
                .forEach(
                        e ->
                                out.add(
                                        e.getKey(),
                                        resolve(root, e.getValue(), new HashSet<>(stack))));
        return out;
    }

    private static String type(JsonObject schema) {
        require(
                schema.has("type") && schema.get("type").isJsonPrimitive(),
                "Schema needs a concrete type; normalize unions first");
        return schema.get("type").getAsString();
    }

    private static Map<String, Object> map(JsonObject value) {
        return GSON.fromJson(
                value, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
    }

    private static JsonObject object(JsonObject value, String key) {
        return value.has(key) ? value.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject value, String key) {
        return value.has(key) ? value.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject value, String key, String fallback) {
        return value.has(key) ? value.get(key).getAsString() : fallback;
    }
}
