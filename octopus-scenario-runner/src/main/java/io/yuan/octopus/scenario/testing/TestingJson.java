package io.yuan.octopus.scenario.testing;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class TestingJson {
    public static final Gson GSON =
            new GsonBuilder()
                    .disableHtmlEscaping()
                    .serializeNulls()
                    .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL)
                    .registerTypeAdapterFactory(new OptionalPreparationAdapter())
                    .create();

    /** Omit only the new optional model field, preserving old locks and all user JSON payload fields. */
    private static final class OptionalPreparationAdapter implements TypeAdapterFactory {
        @Override public <T> TypeAdapter<T> create(Gson gson, com.google.gson.reflect.TypeToken<T> type) {
            if(!java.util.Set.of(TestingModel.FlowNode.class, TestingModel.Step.class, TestingModel.Flow.class,
                    TestingModel.TestPoint.class, TestingModel.TestIR.class, TestingModel.Poll.class, TestingModel.CaseGeneration.class,
                    TestingModel.ExpectationSource.class).contains(type.getRawType())) return null;
            var delegate=gson.getDelegateAdapter(this,type);
            var treeAdapter=gson.getAdapter(JsonElement.class);
            return new TypeAdapter<T>() {
                @Override public void write(com.google.gson.stream.JsonWriter out,T value) throws java.io.IOException {
                    var tree=delegate.toJsonTree(value);
                    if(tree.isJsonObject()) {
                        var object = tree.getAsJsonObject();
                        for (String field : java.util.List.of("preparation", "poll", "steps", "executionSteps", "endAtStepRef", "cleanup", "suppressedOutputCalls", "generatedFrom", "authorizationRef",
                                "taskId", "intentId", "round", "expectationSources", "observationIds", "flags", "observationId"))
                            if(object.has(field) && object.get(field).isJsonNull()) object.remove(field);
                    }
                    treeAdapter.write(out,tree);
                }
                @Override public T read(com.google.gson.stream.JsonReader in) throws java.io.IOException { return delegate.read(in); }
            };
        }
    }

    private TestingJson() {}

    public static <T> T read(Object value, Class<T> type) {
        JsonElement json =
                value instanceof String text
                        ? JsonParser.parseString(text)
                        : GSON.toJsonTree(value);
        checkFields(json, type);
        return GSON.fromJson(json, type);
    }

    private static void checkFields(JsonElement value, java.lang.reflect.Type type) {
        if (value == null || value.isJsonNull()) return;
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            var args = parameterized.getActualTypeArguments();
            if (java.util.Map.class.isAssignableFrom((Class<?>) parameterized.getRawType())) {
                if (!value.isJsonObject()) throw new IllegalArgumentException("Expected object");
                value.getAsJsonObject().entrySet().forEach(e -> checkFields(e.getValue(), args[1]));
            } else if (java.util.Collection.class.isAssignableFrom(
                    (Class<?>) parameterized.getRawType())) {
                if (!value.isJsonArray()) throw new IllegalArgumentException("Expected array");
                value.getAsJsonArray().forEach(e -> checkFields(e, args[0]));
            }
        } else if (type instanceof Class<?> cls && cls.isRecord()) {
            if (!value.isJsonObject())
                throw new IllegalArgumentException("Expected " + cls.getSimpleName());
            var fields = new java.util.HashMap<String, java.lang.reflect.Type>();
            for (var field : cls.getRecordComponents())
                fields.put(field.getName(), field.getGenericType());
            value.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            e -> {
                                if (!fields.containsKey(e.getKey()))
                                    throw new IllegalArgumentException(
                                            "Unsupported field "
                                                    + cls.getSimpleName()
                                                    + "."
                                                    + e.getKey());
                                checkFields(e.getValue(), fields.get(e.getKey()));
                            });
        }
    }

    public static String canonical(Object object) {
        return sorted(GSON.toJsonTree(object)).toString();
    }

    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return new JsonPrimitive(value.getAsBigDecimal().stripTrailingZeros());
        }
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().keySet().stream()
                    .sorted()
                    .forEach(k -> result.add(k, sorted(value.getAsJsonObject().get(k))));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(v -> result.add(sorted(v)));
            return result;
        }
        return value;
    }

    public static String hash(Object object) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical(object).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
