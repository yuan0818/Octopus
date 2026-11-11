package io.yuan.octopus.extension.sdk;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.functions.AbstractFunction;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;

/**
 * Optional base class for Octopus tools. It is a regular JMeter function (extends {@link AbstractFunction}),
 * so JMeter discovers and calls it by its own rules; the annotations on the subclass drive:
 *
 * <ul>
 *   <li>{@link #getReferenceKey()} = {@code __<name>_v<version>} (see {@link ToolNames#referenceKey});
 *   <li>{@link #getArgumentDesc()} from {@link ToolParam#description()};
 *   <li>parameter count checks, type conversion and constraint validation;
 *   <li>output serialization and mapping of {@link ToolException} to declared error codes.
 * </ul>
 *
 * Subclasses implement only {@link #compute(Args)}, which must depend on its arguments alone: it must not
 * read or write JMeter variables or properties, read the previous sample, access files or the network.
 */
public abstract class OctopusFunction extends AbstractFunction {
    /** Error codes produced by the SDK itself. */
    public static final String ARGUMENT_INVALID = "TOOL_ARGUMENT_INVALID";
    public static final String OUTPUT_INVALID = "TOOL_OUTPUT_INVALID";
    public static final String INTERNAL_ERROR = "TOOL_INTERNAL_ERROR";

    private static final Map<Class<?>, Descriptor> DESCRIPTORS = new ConcurrentHashMap<>();
    private final Descriptor descriptor;
    private CompoundVariable[] parameters = new CompoundVariable[0];

    protected OctopusFunction() {
        this.descriptor = DESCRIPTORS.computeIfAbsent(getClass(), Descriptor::of);
    }

    /** Computes the output from typed, validated arguments. */
    protected abstract Object compute(Args args) throws ToolException;

    @Override
    public final String getReferenceKey() {
        return descriptor.referenceKey;
    }

    @Override
    public final List<String> getArgumentDesc() {
        return descriptor.argumentDescriptions;
    }

    @Override
    public final void setParameters(Collection<CompoundVariable> parameters) throws InvalidVariableException {
        checkParameterCount(parameters, descriptor.requiredCount, descriptor.params.size());
        this.parameters = parameters.toArray(new CompoundVariable[0]);
    }

    @Override
    public final String execute(SampleResult previousResult, Sampler currentSampler) {
        var raw = new ArrayList<String>(parameters.length);
        for (CompoundVariable parameter : parameters) raw.add(parameter.execute());
        return invoke(raw);
    }

    /** Converts, validates and computes. Exposed for unit tests of subclasses; never throws. */
    public final String invoke(List<String> rawArguments) {
        Map<String, Object> values;
        try {
            values = descriptor.convert(rawArguments);
        } catch (IllegalArgumentException e) {
            return ToolNames.error(ARGUMENT_INVALID);
        }
        Object result;
        try {
            result = compute(new Args(values));
        } catch (ToolException e) {
            String code = e.code();
            return ToolNames.error(code != null && ToolNames.ERROR_CODE.matcher(code).matches() ? code : INTERNAL_ERROR);
        } catch (RuntimeException e) {
            return ToolNames.error(INTERNAL_ERROR);
        }
        try {
            return descriptor.serialize(result);
        } catch (IllegalArgumentException e) {
            return ToolNames.error(OUTPUT_INVALID);
        }
    }

    /** Parameter metadata read from the subclass annotations once per class. */
    static final class Descriptor {
        final String referenceKey;
        final List<ToolParam> params;
        final int requiredCount;
        final List<String> argumentDescriptions;
        final ParamType outputType;

        private Descriptor(String referenceKey, List<ToolParam> params, int requiredCount, ParamType outputType) {
            this.referenceKey = referenceKey;
            this.params = params;
            this.requiredCount = requiredCount;
            this.outputType = outputType;
            var descriptions = new ArrayList<String>();
            for (ToolParam p : params) descriptions.add(p.name() + (p.required() ? "" : "（可选）") + "：" + p.description());
            this.argumentDescriptions = Collections.unmodifiableList(descriptions);
        }

        static Descriptor of(Class<?> type) {
            OctopusTool tool = type.getAnnotation(OctopusTool.class);
            ToolOutput output = type.getAnnotation(ToolOutput.class);
            if (tool == null || output == null)
                throw new IllegalStateException(type.getName() + " requires @OctopusTool and @ToolOutput");
            var params = new ArrayList<>(Arrays.asList(type.getAnnotationsByType(ToolParam.class)));
            params.sort(Comparator.comparingInt(ToolParam::index));
            int required = 0;
            for (int i = 0; i < params.size(); i++) {
                if (params.get(i).index() != i) throw new IllegalStateException("@ToolParam indexes must be 0.." + (params.size() - 1));
                if (params.get(i).required()) {
                    if (required != i) throw new IllegalStateException("Required parameters must precede optional ones");
                    required++;
                }
            }
            return new Descriptor(ToolNames.referenceKey(tool.name(), tool.version()), Collections.unmodifiableList(params), required, output.type());
        }

        Map<String, Object> convert(List<String> raw) {
            if (raw.size() < requiredCount || raw.size() > params.size()) throw new IllegalArgumentException("argument count");
            var values = new LinkedHashMap<String, Object>();
            for (int i = 0; i < params.size(); i++) {
                ToolParam p = params.get(i);
                String text = i < raw.size() ? raw.get(i) : null;
                if ((text == null || text.isEmpty()) && !p.required()) text = p.defaultValue().isEmpty() ? null : p.defaultValue();
                if (text == null) {
                    if (p.required()) throw new IllegalArgumentException("missing " + p.name());
                    values.put(p.name(), null);
                    continue;
                }
                values.put(p.name(), value(p, text));
            }
            return values;
        }

        private static Object value(ToolParam p, String text) {
            if (p.maxLength() >= 0 && text.length() > p.maxLength()) throw new IllegalArgumentException("too long");
            if (p.enumValues().length > 0 && !Arrays.asList(p.enumValues()).contains(text)) throw new IllegalArgumentException("not allowed");
            if (!p.pattern().isEmpty() && !Pattern.compile(p.pattern()).matcher(text).matches()) throw new IllegalArgumentException("pattern");
            Object value;
            switch (p.type()) {
                case STRING: value = text; break;
                case BOOLEAN:
                    if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException("boolean");
                    value = Boolean.valueOf(text);
                    break;
                case INTEGER: {
                    BigDecimal n = new BigDecimal(text.trim());
                    if (n.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("integer");
                    value = n;
                    break;
                }
                case NUMBER: value = new BigDecimal(text.trim()); break;
                case OBJECT: {
                    Object parsed = Json.parse(text);
                    if (!(parsed instanceof Map)) throw new IllegalArgumentException("object");
                    value = parsed;
                    break;
                }
                default: throw new IllegalArgumentException("type");
            }
            if (value instanceof BigDecimal) {
                BigDecimal n = (BigDecimal) value;
                if (!p.min().isEmpty() && n.compareTo(new BigDecimal(p.min())) < 0) throw new IllegalArgumentException("min");
                if (!p.max().isEmpty() && n.compareTo(new BigDecimal(p.max())) > 0) throw new IllegalArgumentException("max");
            }
            return value;
        }

        String serialize(Object result) {
            if (result == null) throw new IllegalArgumentException("null output");
            switch (outputType) {
                case STRING:
                    if (!(result instanceof String)) throw new IllegalArgumentException("string");
                    return (String) result;
                case BOOLEAN:
                    if (!(result instanceof Boolean)) throw new IllegalArgumentException("boolean");
                    return result.toString();
                case INTEGER: {
                    BigDecimal n = number(result);
                    if (n.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("integer");
                    return n.toBigIntegerExact().toString();
                }
                case NUMBER: return number(result).stripTrailingZeros().toPlainString();
                case OBJECT:
                    if (!(result instanceof Map)) throw new IllegalArgumentException("object");
                    return Json.write(result);
                default: throw new IllegalArgumentException("type");
            }
        }

        private static BigDecimal number(Object result) {
            if (result instanceof BigDecimal) return (BigDecimal) result;
            if (result instanceof Number) return new BigDecimal(result.toString());
            throw new IllegalArgumentException("number");
        }
    }
}
