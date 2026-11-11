package io.yuan.octopus.extension.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import io.yuan.octopus.extension.sdk.ToolNames;
import java.math.BigDecimal;
import java.util.*;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.functions.Function;

/**
 * Calls a published extension inside JMeter. The function class named in the manifest is instantiated
 * directly and each argument becomes its own {@link CompoundVariable}; argument text is escaped with
 * JMeter's own rules first, so no value can be interpreted as a variable or function reference. Model or
 * user text is never concatenated into a {@code ${__function(...)}} string.
 */
public final class ExtensionInvoker {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    public static final int MAX_ARGUMENT_CHARS = 1_000_000;

    private ExtensionInvoker() {}

    /** Escapes text for {@link CompoundVariable#setParameters(String)}: JMeter treats {@code \ $ ,} as escapable. */
    public static String escape(String text) {
        var out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '$' || c == ',') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    /** Text form of a typed argument, matching how the SDK parses each {@code ParamType}. */
    public static String text(Object value, String type) {
        if (value == null) return "";
        switch (type) {
            case "STRING":
                if (!(value instanceof String)) throw new ExtensionFailure("EXT_ARGUMENT_INVALID");
                return (String) value;
            case "BOOLEAN":
                if (!(value instanceof Boolean)) throw new ExtensionFailure("EXT_ARGUMENT_INVALID");
                return value.toString();
            case "INTEGER":
            case "NUMBER":
                if (!(value instanceof Number)) throw new ExtensionFailure("EXT_ARGUMENT_INVALID");
                return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
            case "OBJECT":
                if (!(value instanceof Map)) throw new ExtensionFailure("EXT_ARGUMENT_INVALID");
                return GSON.toJson(value);
            default:
                throw new ExtensionFailure("EXT_ARGUMENT_INVALID");
        }
    }

    /** Converts the function's text result back to the declared output type. */
    public static Object output(String text, String type) {
        if (ToolNames.isError(text)) throw new ExtensionFailure("EXT_" + ToolNames.errorCode(text));
        if (text == null) throw new ExtensionFailure("EXT_OUTPUT_INVALID");
        try {
            switch (type) {
                case "STRING": return text;
                case "BOOLEAN":
                    if (!text.equals("true") && !text.equals("false")) throw new ExtensionFailure("EXT_OUTPUT_INVALID");
                    return Boolean.valueOf(text);
                case "INTEGER": return new BigDecimal(text.trim()).longValueExact();
                case "NUMBER": return new BigDecimal(text.trim());
                case "OBJECT": {
                    Object parsed = GSON.fromJson(text, Object.class);
                    if (!(parsed instanceof Map)) throw new ExtensionFailure("EXT_OUTPUT_INVALID");
                    return parsed;
                }
                default: throw new ExtensionFailure("EXT_OUTPUT_INVALID");
            }
        } catch (ExtensionFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExtensionFailure("EXT_OUTPUT_INVALID");
        }
    }

    /** Executes with named, typed arguments and returns the typed output. */
    public static Object invoke(ExtensionEntry entry, Map<String, Object> args) {
        var raw = new ArrayList<String>();
        int last = -1;
        for (int i = 0; i < entry.params.size(); i++) {
            var p = entry.params.get(i);
            Object v = args.get(p.name);
            if (v == null && p.required) throw new ExtensionFailure("EXT_ARGUMENT_MISSING");
            String text = text(v, p.type);
            if (text.length() > MAX_ARGUMENT_CHARS) throw new ExtensionFailure("EXT_ARGUMENT_TOO_LARGE");
            raw.add(text);
            if (v != null) last = i;
        }
        for (String key : args.keySet())
            if (entry.params.stream().noneMatch(p -> p.name.equals(key))) throw new ExtensionFailure("EXT_ARGUMENT_UNKNOWN");
        // Trailing optional parameters that were not supplied are omitted, so the function applies its default.
        int count = Math.max(last + 1, (int) entry.params.stream().filter(p -> p.required).count());
        return output(call(entry.className, raw.subList(0, count)), entry.outputType);
    }

    /** Calls a JMeter function class with already-textual arguments. Also used by the verification probe. */
    public static String call(String className, List<String> rawArguments) {
        Function function;
        try {
            Class<?> type = Class.forName(className, true, classLoader());
            function = (Function) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            throw new ExtensionFailure("EXT_UNAVAILABLE");
        }
        try {
            var parameters = new LinkedList<CompoundVariable>();
            for (String arg : rawArguments) {
                var variable = new CompoundVariable();
                variable.setParameters(escape(arg));
                parameters.add(variable);
            }
            function.setParameters(parameters);
            return function.execute(null, null);
        } catch (ExtensionFailure e) {
            throw e;
        } catch (Exception e) {
            throw new ExtensionFailure("EXT_EXECUTION_FAILED");
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ExtensionInvoker.class.getClassLoader();
    }
}
