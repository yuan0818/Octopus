package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supervises JMeter's process lifecycle; it does not execute test operations itself. */
@Component
public class JmeterProcessExecutor {
    public record Sample(
            String label, boolean success, String responseCode, long elapsed, String failure) {}

    public record CallResult(String callId,String status,boolean cleanup) {}
    public record Result(String state, String verdict, List<Sample> samples, String error, boolean executionComplete, String cleanupStatus, boolean gatePassed,List<CallResult> calls) {
        public Result(String state,String verdict,List<Sample> samples,String error) {
            this(state,verdict,samples,error,"COMPLETED".equals(state) && error==null,"NOT_REQUIRED","PASS".equals(verdict),List.of());
        }
    }

    public Result execute(
            Path jmeterHome,
            Path directory,
            Compilation compilation,
            ExecutionProfile profile,
            long timeoutSeconds,
            AtomicBoolean cancelled) {
        return execute(jmeterHome, directory, compilation, profile, timeoutSeconds, cancelled, Map.of());
    }

    public Result execute(Path jmeterHome, Path directory, Compilation compilation, ExecutionProfile profile,
                          long timeoutSeconds, AtomicBoolean cancelled,
                          Map<String, io.yuan.octopus.verification.VerificationRuntime.ConnectionSettings> connections) {
        return execute(jmeterHome, directory, compilation, profile, timeoutSeconds, cancelled, connections, Map.of());
    }

    /** properties：额外的 JMeter 属性（-Jkey=value），用于生成探测的证据文件路径；键与值均受限，不能携带凭据。 */
    public Result execute(Path jmeterHome, Path directory, Compilation compilation, ExecutionProfile profile,
                          long timeoutSeconds, AtomicBoolean cancelled,
                          Map<String, io.yuan.octopus.verification.VerificationRuntime.ConnectionSettings> connections,
                          Map<String, String> properties) {
        require(properties != null && properties.entrySet().stream().allMatch(e -> e.getKey().matches("octopus\\.[A-Za-z0-9.]{1,64}")
                && e.getValue() != null && e.getValue().length() <= 1024 && !e.getValue().contains("\n")), "Unsupported JMeter property");
        require(
                timeoutSeconds >= 1 && timeoutSeconds <= 3600,
                "Execution timeout must be 1..3600 seconds");
        verifyRuntime(jmeterHome);
        verifyCapabilities(jmeterHome, compilation);
        Path contextFile = directory.resolve("context.json");
        Path connectionsFile = directory.resolve("connections.json");
        Path environmentFile = directory.resolve("environment.json");
        Process process = null;
        try {
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            Files.writeString(directory.resolve("plan.jmx"), compilation.jmx());
            Files.writeString(
                    directory.resolve("source-map.json"), canonical(compilation.sourceMap()));
            Files.writeString(
                    directory.resolve("capabilities-lock.json"),
                    canonical(compilation.capabilities()));
            Files.createFile(contextFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(
                    contextFile,
                    canonical(
                            profile.initialContext() == null
                                    ? Map.of()
                                    : profile.initialContext()));
            // The private, ephemeral file is injected only into this child process; never into JMX/IR.
            Files.createFile(connectionsFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(connectionsFile, canonical(connections));
            Files.createFile(environmentFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(environmentFile, canonical(profile.callEnvironments()==null ? Map.of() : profile.callEnvironments()));
            if (cancelled.get()) return report("CANCELLED",List.of(),compilation,null);
            List<String> command =
                    new ArrayList<>(
                            List.of(
                                    jmeterHome.resolve("bin/jmeter").toAbsolutePath().toString(),
                                    "-n",
                                    "-t",
                                    directory.resolve("plan.jmx").toAbsolutePath().toString(),
                                    "-l",
                                    directory.resolve("results.jtl").toAbsolutePath().toString(),
                                    "-j",
                                    directory.resolve("jmeter.log").toAbsolutePath().toString(),
                                    "-Joctopus.contextFile=" + contextFile.toAbsolutePath(),
                                    "-Joctopus.environmentFile=" + environmentFile.toAbsolutePath(),
                                    "-Joctopus.connectionsFile=" + connectionsFile.toAbsolutePath(),
                                    "-Lorg.apache.kafka=OFF",
                                    "-Jjmeter.save.saveservice.output_format=csv",
                                    "-Jjmeter.save.saveservice.print_field_names=true",
                                    "-Jjmeter.save.saveservice.autoflush=true",
                                    "-Jjmeter.save.saveservice.assertion_results_failure_message=true",
                                    "-Jjmeter.save.saveservice.response_data=false",
                                    "-Jjmeter.save.saveservice.response_data.on_error=false",
                                    "-Jjmeter.save.saveservice.samplerData=false",
                                    "-Jjmeter.save.saveservice.requestHeaders=false",
                                    "-Jjmeter.save.saveservice.responseHeaders=false",
                                    "-Jjmeter.save.saveservice.url=false",
                                    "-Jjmeter.save.saveservice.response_message=false",
                                    "-Jjmeterengine.nongui.port=0",
                                    // FR-LIN-04: never resend a business request at the transport layer.
                                    "-Jhttpclient4.retrycount=0",
                                    "-Jhttpclient4.request_sent_retry_enabled=false"));
            new java.util.TreeMap<>(properties).forEach((key, value) -> command.add("-J" + key + "=" + value));
            var builder=new ProcessBuilder(command)
                    .directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("process.log").toFile());
            // Match the runner/extension bytecode, independent of a desktop shell's older Java.
            builder.environment().put("JAVA_HOME",System.getProperty("java.home"));
            builder.environment().put("JRE_HOME",System.getProperty("java.home"));
            process=builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.get() || System.nanoTime() >= deadline) {
                    terminate(process);
                    var partial=readSamples(directory.resolve("results.jtl"));
                    return report(cancelled.get() ? "CANCELLED" : "TIMED_OUT",partial,compilation,null);
                }
            }
            if (cancelled.get()) {
                var partial=readSamples(directory.resolve("results.jtl"));
                return report("CANCELLED",partial,compilation,null);
            }
            List<Sample> samples = readSamples(directory.resolve("results.jtl"));
            if (process.exitValue() != 0)
                return report("FAILED",samples,compilation,"JMeter process exit: "+process.exitValue());
            return report("COMPLETED",samples,compilation,null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) terminate(process);
            List<Sample> partial;
            try { partial=readSamples(directory.resolve("results.jtl")); } catch(Exception unreadable) {partial=List.of();}
            return report("FAILED",partial,compilation,"JMeter execution failed: " + e.getClass().getSimpleName());
        } finally {
            try {
                Files.deleteIfExists(contextFile);
                Files.deleteIfExists(connectionsFile);
                Files.deleteIfExists(environmentFile);
            } catch (IOException ignored) {
            }
        }
    }

    static Result report(String state,List<Sample> samples,Compilation compilation,String error) {
        Set<String> cleanup=new HashSet<>(),requiredCleanup=new HashSet<>();
        Set<String> notApplicable=new HashSet<>();
        samples.stream().filter(s->s.label().startsWith("dispatch:cleanup:") && "CLEANUP_NOT_APPLICABLE".equals(s.responseCode()))
                .forEach(s->notApplicable.add(s.label().substring("dispatch:cleanup:".length())));
        compilation.sourceMap().forEach((label,source)->{
            if(source.contains("/cleanup/") || source.contains("/cleanup-required/")) cleanup.add(label);
            if(source.contains("/cleanup-required/") && notApplicable.stream().noneMatch(id->source.contains("/cleanup-required/"+id+"#"))) requiredCleanup.add(label);
        });
        Set<String> required=new HashSet<>(compilation.sourceMap().keySet());required.removeAll(cleanup);
        Map<String,Sample> actual=new HashMap<>();samples.forEach(s->actual.put(s.label(),s));
        var business=samples.stream().filter(s->!cleanup.contains(s.label()) && !s.label().startsWith("dispatch:cleanup:")).toList();
        boolean complete="COMPLETED".equals(state) && !business.isEmpty() && actual.keySet().containsAll(required);
        String verdict=verdict(business,complete);
        boolean cleanupFailed=samples.stream().anyMatch(s->cleanup.contains(s.label()) && !s.success());
        boolean cleanupComplete=cleanup.stream().allMatch(label->actual.containsKey(label) || notApplicable.stream()
                .anyMatch(id->compilation.sourceMap().get(label).contains("/"+id+"#")));
        String cleanupStatus=cleanup.isEmpty()?"NOT_REQUIRED":cleanupFailed?"FAIL":cleanupComplete?"PASS":
                samples.stream().anyMatch(s->"CLEANUP_NOT_RUN".equals(s.responseCode()))?"NOT_RUN":"UNKNOWN";
        if(!cleanup.isEmpty() && cleanup.stream().noneMatch(actual::containsKey) && cleanupComplete) cleanupStatus="NOT_APPLICABLE";
        boolean cleanupGate=requiredCleanup.stream().allMatch(label->actual.containsKey(label) && actual.get(label).success());
        List<CallResult> calls=new ArrayList<>();
        for(var entry:compilation.sourceMap().entrySet()) if(entry.getKey().startsWith("step:")) {
            String id=entry.getKey().substring(5),source=entry.getValue();
            var labels=compilation.sourceMap().entrySet().stream().filter(e->e.getValue().equals(source) || e.getValue().startsWith(source+"/"))
                    .map(Map.Entry::getKey).toList();
            var callSamples=samples.stream().filter(s->labels.contains(s.label())).toList();
            String status;
            if(notApplicable.contains(id)) status="NOT_APPLICABLE";
            else if(callSamples.isEmpty()) status=samples.stream().anyMatch(s->"FLOW_BLOCKED".equals(s.responseCode()))?"BLOCKED":"NOT_RUN";
            else if(callSamples.stream().anyMatch(s->!s.success())) status="FAIL".equals(verdict(callSamples,false))?"FAIL":"ERROR";
            else status=actual.keySet().containsAll(labels)?"PASS":"UNKNOWN";
            calls.add(new CallResult(id,status,cleanup.contains(entry.getKey())));
        }
        return new Result(state,verdict,samples,error!=null?error:complete?null:"Required operations did not all execute",complete,cleanupStatus,
                "PASS".equals(verdict) && cleanupGate,List.copyOf(calls));
    }

    static String verdict(List<Sample> samples, boolean complete) {
        // A later runtime error cannot erase an already observed business failure.
        boolean failed=samples.stream().anyMatch(s -> !s.success() &&
                ("VERIFY_MISMATCH".equals(s.responseCode()) || "FLOW_ASSERTION_FAILED".equals(s.responseCode()) || "CONTEXT_INVALID".equals(s.responseCode())
                        || s.responseCode().matches("[1-5][0-9]{2}") && s.failure().startsWith("Business verification failed:")));
        if(failed) return "FAIL";
        return complete && samples.stream().allMatch(Sample::success) ? "PASS" : "INCONCLUSIVE";
    }

    public void verifyRuntime(Path home) {
        require(
                home != null && Files.isExecutable(home.resolve("bin/jmeter")),
                "JMeter runtime unavailable; configure JMETER_HOME");
        require(
                Files.isRegularFile(home.resolve("bin/saveservice.properties")),
                "Incomplete JMeter distribution");
        require(
                Files.isRegularFile(home.resolve("lib/ext/ApacheJMeter_http.jar")),
                "Missing capability runtime: http");
        try (var jar =
                new java.util.jar.JarFile(home.resolve("lib/ext/ApacheJMeter_core.jar").toFile())) {
            String version =
                    jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            require(
                    "5.6.3".equals(version),
                    "Compiler requires JMeter 5.6.3; runtime version: " + version);
        } catch (IOException e) {
            throw new IllegalArgumentException("JMeter core runtime unavailable", e);
        }
        try (var files = Files.list(home.resolve("lib"))) {
            require(
                    files.anyMatch(
                            p ->
                                    p.getFileName().toString().startsWith("groovy-json-")
                                            && p.toString().endsWith(".jar")),
                    "Missing capability runtime: json/Groovy");
        } catch (IOException e) {
            throw new IllegalArgumentException("JMeter libraries unavailable", e);
        }
    }

    public void verifyCapabilities(Path home, Compilation compilation) {
        boolean preparation=compilation.capabilities().stream().anyMatch(c -> c.id().equals("request.prepare"));
        if (!preparation && compilation.capabilities().stream().noneMatch(c -> c.id().startsWith("verify."))) return;
        require(home != null && Files.isRegularFile(home.resolve("lib/ext/octopus-jmeter-ext-1.jar")), "缺少 JMeter 验证扩展，请安装 octopus-jmeter-ext 的 distribution");
        try (var jar = new java.util.jar.JarFile(home.resolve("lib/ext/octopus-jmeter-ext-1.jar").toFile())) {
            require("1".equals(jar.getManifest().getMainAttributes().getValue("Implementation-Version"))
                    && jar.getEntry("io/yuan/octopus/verification/VerificationRuntime.class") != null, "JMeter 验证扩展版本或内容不匹配");
            if(preparation) require("1".equals(jar.getManifest().getMainAttributes().getValue("Octopus-Preparation-Version")) && jar.getEntry("io/yuan/octopus/preparation/PreparationRuntime.class")!=null,"JMeter 请求预处理扩展尚未安装，请更新 distribution");
            if(compilation.capabilities().stream().anyMatch(c -> c.id().equals(ResponseSchemaChecks.CAPABILITY)))
                require(jar.getEntry(ResponseSchemaChecks.RUNTIME_CLASS_ENTRY)!=null,"JMeter 扩展版本过旧，缺少回应契约校验，请更新 octopus-jmeter-ext 的 distribution");
        } catch (IOException e) { throw new IllegalArgumentException("JMeter 验证扩展不可读取"); }
        try (var files = Files.list(home.resolve("lib"))) {
            var names = files.map(p -> p.getFileName().toString()).toList();
            require(names.stream().anyMatch(n -> n.startsWith("gson-") && n.endsWith(".jar")), "JMeter 缺少验证依赖: gson");
            if(preparation) for(String prefix:List.of("jackson-core-","jackson-databind-","jackson-annotations-","hutool-core-5.8.16"))
                require(names.stream().anyMatch(n -> n.startsWith(prefix) && n.endsWith(".jar")),"JMeter 缺少预处理依赖: "+prefix);
            for (var c : compilation.capabilities()) {
                var prefixes = switch (c.id()) {
                    case "verify.sql" -> List.of("mysql-connector-j-", "postgresql-");
                    case "verify.redis" -> List.of("jedis-", "commons-pool2-");
                    case "verify.kafka" -> List.of("kafka-clients-", "lz4-java-", "snappy-java-", "zstd-jni-");
                    default -> List.<String>of();
                };
                for (String prefix : prefixes) require(names.stream().anyMatch(n -> n.startsWith(prefix) && n.endsWith(".jar")), "JMeter 缺少验证依赖: " + prefix);
            }
        } catch (IOException e) { throw new IllegalArgumentException("JMeter 验证依赖目录不可用"); }
    }

    private static void terminate(Process process) {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }

    static List<Sample> readSamples(Path file) throws IOException {
        if (!Files.exists(file)) return List.of();
        List<Sample> samples = new ArrayList<>();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return samples;
            List<String> columns = csv(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = csv(line);
                if (row.size() != columns.size()) continue;
                Map<String, String> values = new HashMap<>();
                for (int i = 0; i < columns.size(); i++) values.put(columns.get(i), row.get(i));
                String label = values.getOrDefault("label", "");
                if (!label.startsWith("step:") && !label.startsWith("guard:") && !label.startsWith("verify:") && !label.startsWith("observe:") && !label.startsWith("prepare:") && !label.startsWith("complete:") && !label.startsWith("dispatch:")) continue;
                samples.add(
                        new Sample(
                                label,
                                "true".equals(values.get("success")),
                                values.getOrDefault("responseCode", ""),
                                Long.parseLong(values.getOrDefault("elapsed", "0")),
                                values.getOrDefault("responseCode", "").startsWith("PREPARE_") ? preparationFailure(values.get("responseCode"))
                                        : values.getOrDefault("responseCode", "").equals("VERIFY_ERROR") ? "验证连接、权限、结构或观察范围不可用"
                                        : values.getOrDefault("responseCode", "").equals("VERIFY_MISMATCH") ? "等待窗口内未观察到预期业务结果"
                                        : values.getOrDefault("failureMessage", "")));
            }
        }
        return samples;
    }

    private static String preparationFailure(String code) {
        return switch(code) {
            case "PREPARE_CREDENTIAL_MISSING" -> "运行时凭据未配置或为空，请检查引用对应的服务环境变量";
            case "PREPARE_PUBLIC_KEY_INVALID" -> "RSA 公钥格式无效，需要 X.509 PUBLIC KEY PEM 或 Base64";
            case "PREPARE_ALGORITHM_UNSUPPORTED" -> "签名算法不支持，请确认当前环境使用 MD5 或 SHA-256";
            case "PREPARE_INPUT_MISSING" -> "预处理需要的上下文或参数尚未产生";
            case "PREPARE_HEADER_INVALID" -> "生成的请求头包含非法字符，请求未发送";
            case "PREPARE_TOOL_UNAVAILABLE" -> "JMeter 请求预处理扩展缺失或版本不匹配";
            case "PREPARE_INPUT_TOO_LARGE" -> "预处理输入超过大小限制";
            case "PREPARE_CRYPTO_FAILED" -> "加密失败，请检查密钥与协议配置，请求未发送";
            default -> "预处理参数或绑定格式不正确，请求未发送";
        };
    }

    private static List<String> csv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append(c);
                    i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(value.toString());
                value.setLength(0);
            } else value.append(c);
        }
        fields.add(value.toString());
        return fields;
    }
}
