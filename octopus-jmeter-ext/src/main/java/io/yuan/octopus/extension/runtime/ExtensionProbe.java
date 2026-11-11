package io.yuan.octopus.extension.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.yuan.octopus.extension.sdk.ToolNames;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.jmeter.functions.Function;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.reflect.ClassFinder;

/**
 * Entry point of the isolated verification process started by the platform for an uploaded JAR.
 * It discovers functions exactly as JMeter does ({@link ClassFinder}, {@code .functions.} / not {@code .gui.}),
 * checks reference keys and argument descriptions, and executes every declared example.
 *
 * <p>Usage: {@code ExtensionProbe <spec.json> <result.json>}. Never prints arguments or outputs.
 */
public final class ExtensionProbe {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static final class Spec {
        public String jmeterHome;
        public String jar;
        public long callTimeoutMillis = 100;
        /** Only for platform "try" calls with literal, non-sensitive arguments; never set for registration. */
        public boolean returnOutputs;
        public List<ClassSpec> classes = new ArrayList<>();
    }

    public static final class ClassSpec {
        public String className;
        public String referenceKey;
        public int paramCount;
        public String determinism;
        public List<Example> examples = new ArrayList<>();
    }

    public static final class Example {
        public List<String> args = new ArrayList<>();
        public String output;
        public String match = "EXACT";
    }

    public static final class Result {
        public List<String> discovered = new ArrayList<>();
        public List<ClassResult> classes = new ArrayList<>();
        public String error;
    }

    public static final class ClassResult {
        public String className;
        public boolean discovered;
        public String referenceKey;
        public int argumentDescriptions = -1;
        public List<ExampleResult> examples = new ArrayList<>();
        public List<String> problems = new ArrayList<>();
    }

    public static final class ExampleResult {
        public int index;
        public boolean matched;
        public boolean repeatable = true;
        public long millis;
        public String errorCode;
        public String output;
    }

    private ExtensionProbe() {}

    public static void main(String[] args) throws Exception {
        Spec spec = GSON.fromJson(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8), Spec.class);
        Result result = run(spec);
        Files.write(Paths.get(args[1]), GSON.toJson(result).getBytes(StandardCharsets.UTF_8));
    }

    public static Result run(Spec spec) {
        var result = new Result();
        try {
            if (spec.jmeterHome != null) {
                JMeterUtils.setJMeterHome(spec.jmeterHome);
                Path properties = Paths.get(spec.jmeterHome, "bin", "jmeter.properties");
                if (Files.isRegularFile(properties)) JMeterUtils.loadJMeterProperties(properties.toString());
            }
            result.discovered.addAll(ClassFinder.findClassesThatExtend(new String[] {spec.jar}, new Class<?>[] {Function.class}, true, ".functions.", ".gui."));
        } catch (Exception | LinkageError e) {
            result.error = "DISCOVERY_FAILED";
            return result;
        }
        for (ClassSpec c : spec.classes) result.classes.add(check(spec, c, result.discovered.contains(c.className)));
        return result;
    }

    private static ClassResult check(Spec spec, ClassSpec c, boolean discovered) {
        var r = new ClassResult();
        r.className = c.className;
        r.discovered = discovered;
        if (!discovered) r.problems.add("JMeter 未发现该函数类（需实现 Function，类名含 .functions. 且不含 .gui.）");
        Function function;
        try {
            function = (Function) Class.forName(c.className).getDeclaredConstructor().newInstance();
            r.referenceKey = function.getReferenceKey();
            r.argumentDescriptions = function.getArgumentDesc() == null ? 0 : function.getArgumentDesc().size();
        } catch (Exception | LinkageError e) {
            r.problems.add("无法实例化函数类（需要公共无参构造器）");
            return r;
        }
        if (!Objects.equals(c.referenceKey, r.referenceKey)) r.problems.add("getReferenceKey() 与元数据不一致，应为 " + c.referenceKey);
        if (r.argumentDescriptions != c.paramCount) r.problems.add("getArgumentDesc() 数量与 @ToolParam 不一致");
        for (int i = 0; i < c.examples.size(); i++) {
            Example example = c.examples.get(i);
            var er = new ExampleResult();
            er.index = i;
            String first = null;
            try {
                long start = System.nanoTime();
                first = ExtensionInvoker.call(c.className, example.args);
                er.millis = (System.nanoTime() - start) / 1_000_000;
                if ("PURE".equals(c.determinism)) {
                    String second = ExtensionInvoker.call(c.className, example.args);
                    er.repeatable = Objects.equals(first, second);
                }
            } catch (ExtensionFailure e) {
                er.errorCode = e.code;
            } catch (RuntimeException | LinkageError e) {
                er.errorCode = "EXT_EXECUTION_FAILED";
            }
            if (spec.returnOutputs && first != null && !ToolNames.isError(first)) er.output = first;
            if (first != null && ToolNames.isError(first)) er.errorCode = ToolNames.errorCode(first);
            else if (first != null)
                er.matched = example.output == null || ("REGEX".equals(example.match) ? Pattern.compile(example.output).matcher(first).matches() : example.output.equals(first));
            if (er.errorCode != null) r.problems.add("示例 " + (i + 1) + " 执行失败：" + er.errorCode);
            else if (!er.matched) r.problems.add("示例 " + (i + 1) + " 的输出与声明不一致");
            if (!er.repeatable) r.problems.add("示例 " + (i + 1) + " 两次执行结果不同，但声明为 PURE");
            if (er.millis > spec.callTimeoutMillis) r.problems.add("示例 " + (i + 1) + " 耗时 " + er.millis + " ms，超过上限 " + spec.callTimeoutMillis + " ms");
            r.examples.add(er);
        }
        return r;
    }
}
