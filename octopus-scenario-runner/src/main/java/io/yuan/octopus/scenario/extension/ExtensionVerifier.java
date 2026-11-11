package io.yuan.octopus.scenario.extension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.yuan.octopus.extension.runtime.ExtensionProbe;
import io.yuan.octopus.scenario.extension.ExtensionModel.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Isolated test execution of an uploaded JAR (FR-EXT-24). A separate JVM with an empty environment, a
 * temporary working directory and a hard timeout runs {@link ExtensionProbe}: JMeter-style discovery,
 * reference key and argument checks, and every declared example (PURE examples twice).
 *
 * <p>Only the JMeter core, the Octopus runtime/SDK and the uploaded JAR are on the classpath; other
 * published extensions are not, so they cannot interfere.
 */
public final class ExtensionVerifier {
    public static final long PROCESS_TIMEOUT_SECONDS = 60;
    public static final long CALL_TIMEOUT_MILLIS = 100;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path javaBinary;
    private final Path jmeterHome;
    private final List<Path> runtimeClasspath;

    public ExtensionVerifier(Path javaBinary, Path jmeterHome, List<Path> runtimeClasspath) {
        this.javaBinary = javaBinary;
        this.jmeterHome = jmeterHome;
        this.runtimeClasspath = List.copyOf(runtimeClasspath);
    }

    /**
     * The probe classpath for a JMeter installation with the Octopus distribution installed:
     * {@code lib/*.jar} (jorphan, SDK, Gson …), JMeter core and the Octopus runtime from {@code lib/ext}.
     */
    public static List<Path> classpathFor(Path home) throws IOException {
        var result = new ArrayList<Path>();
        try (var files = Files.list(home.resolve("lib"))) {
            files.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(result::add);
        }
        for (String name : List.of("ApacheJMeter_core.jar", "ApacheJMeter_functions.jar", "octopus-jmeter-ext-1.jar")) {
            Path jar = home.resolve("lib/ext").resolve(name);
            if (Files.isRegularFile(jar)) result.add(jar);
        }
        return result;
    }

    /** Whether the installed Octopus runtime contains the verification probe. */
    public static boolean probeInstalled(Path home) {
        Path jar = home.resolve("lib/ext/octopus-jmeter-ext-1.jar");
        if (!Files.isRegularFile(jar)) return false;
        try (var file = new java.util.jar.JarFile(jar.toFile())) {
            return file.getEntry("io/yuan/octopus/extension/runtime/ExtensionProbe.class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs the probe; problems become ERROR issues. Returns the issues found (empty means verified). */
    public List<Issue> verify(byte[] jarBytes, List<Tool> tools) {
        var spec = new ExtensionProbe.Spec();
        spec.callTimeoutMillis = CALL_TIMEOUT_MILLIS;
        for (Tool tool : tools) {
            var c = classSpec(tool);
            for (Example e : tool.examples) {
                var x = new ExtensionProbe.Example();
                x.args = e.args;
                x.output = e.output;
                x.match = e.match;
                c.examples.add(x);
            }
            spec.classes.add(c);
        }
        var issues = new ArrayList<Issue>();
        var run = run(jarBytes, spec, issues);
        if (run == null) return issues;
        for (var c : run.classes)
            for (String problem : c.problems) issues.add(new Issue("ERROR", "VERIFY_PROBLEM", problem, c.className));
        return issues;
    }

    /** Outcome of a "try" call: the output text, or a fixed error code. */
    public static final class Trial {
        public final String output;
        public final String errorCode;
        public final long millis;

        public Trial(String output, String errorCode, long millis) {
            this.output = output;
            this.errorCode = errorCode;
            this.millis = millis;
        }
    }

    /** FR-FN-25: executes one PURE tool with literal arguments in the same isolated process and returns its output. */
    public Trial trial(byte[] jarBytes, Tool tool, List<String> args) {
        var spec = new ExtensionProbe.Spec();
        spec.callTimeoutMillis = CALL_TIMEOUT_MILLIS;
        spec.returnOutputs = true;
        var c = classSpec(tool);
        var x = new ExtensionProbe.Example();
        x.args = args;
        x.output = null;
        c.examples.add(x);
        spec.classes.add(c);
        var issues = new ArrayList<Issue>();
        var run = run(jarBytes, spec, issues);
        if (run == null) return new Trial(null, issues.isEmpty() ? "TRY_FAILED" : issues.get(0).code, 0);
        var example = run.classes.isEmpty() || run.classes.get(0).examples.isEmpty() ? null : run.classes.get(0).examples.get(0);
        if (example == null) return new Trial(null, "TRY_FAILED", 0);
        return new Trial(example.output, example.errorCode, example.millis);
    }

    private static ExtensionProbe.ClassSpec classSpec(Tool tool) {
        var c = new ExtensionProbe.ClassSpec();
        c.className = tool.className;
        c.referenceKey = tool.referenceKey;
        c.paramCount = tool.params.size();
        c.determinism = tool.determinism;
        return c;
    }

    private ExtensionProbe.Result run(byte[] jarBytes, ExtensionProbe.Spec spec, List<Issue> issues) {
        Path work = null;
        try {
            work = Files.createTempDirectory("octopus-extension-verify");
            Path jar = work.resolve("extension.jar");
            Files.write(jar, jarBytes);
            spec.jmeterHome = jmeterHome == null ? null : jmeterHome.toAbsolutePath().toString();
            spec.jar = jar.toAbsolutePath().toString();
            Path specFile = work.resolve("spec.json"), resultFile = work.resolve("result.json");
            Files.write(specFile, GSON.toJson(spec).getBytes(StandardCharsets.UTF_8));
            var classpath = new StringJoiner(File.pathSeparator);
            runtimeClasspath.forEach(p -> classpath.add(p.toAbsolutePath().toString()));
            classpath.add(jar.toAbsolutePath().toString());
            var builder = new ProcessBuilder(javaBinary.toString(), "-Xmx256m", "-Djava.io.tmpdir=" + work.toAbsolutePath(),
                    "-Dlog4j2.level=OFF", "-cp", classpath.toString(), ExtensionProbe.class.getName(),
                    specFile.toAbsolutePath().toString(), resultFile.toAbsolutePath().toString());
            builder.directory(work.toFile());
            builder.environment().clear();
            builder.redirectErrorStream(true);
            builder.redirectOutput(work.resolve("probe.log").toFile());
            Process process = builder.start();
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                issues.add(new Issue("ERROR", "VERIFY_TIMEOUT", "隔离测试超过 " + PROCESS_TIMEOUT_SECONDS + " 秒未完成", null));
                return null;
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(resultFile)) {
                issues.add(new Issue("ERROR", "VERIFY_FAILED", "隔离测试进程异常退出（退出码 " + process.exitValue() + "）", null));
                return null;
            }
            var result = GSON.fromJson(new String(Files.readAllBytes(resultFile), StandardCharsets.UTF_8), ExtensionProbe.Result.class);
            if (result.error != null) {
                issues.add(new Issue("ERROR", "VERIFY_" + result.error, "JMeter 函数发现失败", null));
                return null;
            }
            return result;
        } catch (IOException e) {
            issues.add(new Issue("ERROR", "VERIFY_UNAVAILABLE", "无法启动隔离测试进程", null));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            issues.add(new Issue("ERROR", "VERIFY_INTERRUPTED", "隔离测试被中断", null));
            return null;
        } finally {
            if (work != null) deleteQuietly(work);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }
}
