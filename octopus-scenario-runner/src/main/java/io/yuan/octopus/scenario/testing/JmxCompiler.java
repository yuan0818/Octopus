package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.apache.jmeter.assertions.JSR223Assertion;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.SwitchController;
import org.apache.jmeter.control.WhileController;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.java.sampler.JSR223Sampler;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Pure lowering to a portable JMX. Protocol execution remains entirely inside JMeter. */
@Component
public class JmxCompiler {
    private static boolean initialized;
    private final IrValidator validator;
    private final CapabilityRegistry registry;

    public JmxCompiler(IrValidator validator, CapabilityRegistry registry) {
        this.validator = validator;
        this.registry = registry;
    }

    public Compilation compile(TestIR ir, ExecutionProfile profile) {
        List<Step> ordered = new ArrayList<>(validator.validate(ir));
        Map<String,CleanupStep> cleanups=new HashMap<>();
        if(ir.cleanup()!=null) ir.cleanup().forEach(c->{ordered.add(c.call());cleanups.put(c.call().id(),c);});
        require(
                profile != null && profile.services() != null,
                "Service environment mappings required");
        initialize();
        Map<String, Operation> operations = new HashMap<>();
        ir.operations().forEach(o -> operations.put(o.ref(), o));
        Map<String, String> sourceMap = new TreeMap<>();
        TestPlan plan = new TestPlan("Octopus " + ir.scenarioId());
        tag(plan, "org.apache.jmeter.control.gui.TestPlanGui");
        plan.setUserDefinedVariables(new Arguments());
        plan.setSerialized(ir.executionSteps() != null);
        ListedHashTree tree = new ListedHashTree();
        var planTree = tree.add(plan);
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setFirst(true);
        tag(loop, "org.apache.jmeter.control.gui.LoopControlPanel");
        ThreadGroup group = new ThreadGroup();
        group.setName("Scenario");
        group.setNumThreads(1);
        group.setRampUp(0);
        group.setSamplerController(loop);
        group.setProperty(ThreadGroup.ON_SAMPLE_ERROR, ThreadGroup.ON_SAMPLE_ERROR_STOPTHREAD);
        tag(group, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        var legacySequence = ir.executionSteps() == null ? planTree.add(group) : null;
        Map<String, org.apache.jorphan.collections.HashTree> callTrees = new HashMap<>();
        Map<String, CallGroup> callGroups = new HashMap<>();
        if (ir.executionSteps() != null) {
            int groupIndex = 0;
            var stages=new ArrayList<>(ir.executionSteps());
            if(ir.cleanup()!=null) ir.cleanup().forEach(c->stages.add(new CallGroup("cleanup:"+c.call().id(),"SERIAL",List.of(c.call().id()))));
            for (var stage : stages) {
                LoopController dispatchLoop = new LoopController();
                dispatchLoop.setLoops(stage.callIds().size());
                dispatchLoop.setContinueForever(false);
                tag(dispatchLoop, "org.apache.jmeter.control.gui.LoopControlPanel");
                ThreadGroup workers = new ThreadGroup();
                workers.setName("step:" + stage.id());
                workers.setNumThreads("PARALLEL".equals(stage.mode()) ? Math.min(4, stage.callIds().size()) : 1);
                workers.setRampUp(0);
                workers.setSamplerController(dispatchLoop);
                workers.setProperty(ThreadGroup.ON_SAMPLE_ERROR, ThreadGroup.ON_SAMPLE_ERROR_STOPTHREAD);
                tag(workers, "org.apache.jmeter.threads.gui.ThreadGroupGui");
                var workerTree = planTree.add(workers);
                var dispatch = new JSR223Sampler();
                dispatch.setName("dispatch:" + stage.id());
                Map<String,Object> dispatchSpec = new LinkedHashMap<>();
                dispatchSpec.put("group", stage);
                dispatchSpec.put("groupIndex", groupIndex++);
                dispatchSpec.put("cleanup",cleanups.get(stage.callIds().getFirst()));
                dispatchSpec.put("initialContext", ir.initialContext());
                dispatchSpec.put("calls", ordered.stream().filter(c -> stage.callIds().contains(c.id())).toList());
                dispatchSpec.put("operations", operations);
                dispatchSpec.put("secretEnvironmentNames", profile.secretEnvironmentNames() == null ? Map.of() : profile.secretEnvironmentNames());
                script(dispatch, "dispatch.groovy", encode(dispatchSpec), "octopus-dispatch-v1");
                tag(dispatch, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
                workerTree.add(dispatch);
                SwitchController choose = new SwitchController();
                choose.setName("Select allocated call");
                choose.setSelection("${oct.call.index}");
                tag(choose, "org.apache.jmeter.control.gui.SwitchControllerGui");
                var choices = workerTree.add(choose);
                for (String id : stage.callIds()) {
                    GenericController call = new GenericController();
                    call.setName("call:" + id);
                    tag(call, "org.apache.jmeter.control.gui.LogicControllerGui");
                    callTrees.put(id, choices.add(call));
                    callGroups.put(id, stage);
                }
            }
        }
        for (Step step : ordered) {
            var sequence = ir.executionSteps() == null ? legacySequence : callTrees.get(step.id());
            Operation operation = operations.get(step.operationRef());
            for(var input:step.inputs().entrySet()) if("environment".equals(input.getValue().source())) {
                var snapshot=profile.callEnvironments()==null?null:profile.callEnvironments().get(step.id());
                require(snapshot!=null && snapshot.inputs().containsKey(input.getKey())
                        && IrValidator.matches(snapshot.inputs().get(input.getKey()),operation.inputs().get(input.getKey()).type()),
                        "Environment input is not resolved: "+step.id()+"."+input.getKey());
            }
            Target target = profile.services().getOrDefault("call:"+step.id(),profile.services().get(operation.system() + "." + operation.service()));
            require(
                    target != null,
                    "Missing service target: " + operation.system() + "." + operation.service());
            URI uri = targetUri(target.baseUrl());
            Map<String, Object> payload = new TreeMap<>();
            payload.put("step", step);
            payload.put("preparedInputs", operation.inputs().entrySet().stream().filter(e -> PreparationChecks.supplies(step.preparation(),e.getValue())).map(Map.Entry::getKey).toList());
            payload.put("operation", operation);
            payload.put("initialContext", ir.initialContext());
            payload.put("stateModels", ir.stateModels());
            payload.put("negativeRule", ir.intentionallyViolatedRule());
            // SCHEMA_CONFORMS：服务端先解析出每个断言实际使用的 Schema，运行时模板只做校验
            Map<String, Object> responseSchemas = new TreeMap<>();
            for (var a : step.assertions())
                if (ResponseSchemaChecks.KIND.equals(a.kind()))
                    responseSchemas.put(a.id(), ResponseSchemaChecks.schemaFor(a, step, operation));
            if (!responseSchemas.isEmpty()) payload.put("responseSchemas", responseSchemas);
            payload.put(
                    "secretEnvironmentNames",
                    profile.secretEnvironmentNames() == null
                            ? Map.of()
                            : profile.secretEnvironmentNames());
            payload.put(
                    "basePath",
                    uri.getRawPath() == null ? "" : uri.getRawPath().replaceFirst("/$", ""));
            String encoded =
                    Base64.getEncoder()
                            .encodeToString(canonical(payload).getBytes(StandardCharsets.UTF_8));
            JSR223Sampler guard = new JSR223Sampler();
            guard.setName("guard:" + step.id());
            script(guard, "guard.groovy", encoded, "octopus-guard-v1");
            tag(guard, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
            var guardTree = sequence.add(guard);
            if (step.waitMillis() > 0) {
                ConstantTimer timer = new ConstantTimer();
                timer.setName("wait:" + step.id());
                timer.setDelay(Long.toString(step.waitMillis()));
                tag(timer, "org.apache.jmeter.timers.gui.ConstantTimerGui");
                guardTree.add(timer);
            }
            for (var check : DatastoreChecks.of(step)) if ("KAFKA".equals(DatastoreChecks.plan(check).kind()))
                verification(sequence, ir, step, check, true, sourceMap);
            // Phase 2.1：轮询时把预处理与请求放进 While 循环；条件变量由 guard 初始化、assert 在最后一轮置 false
            var requestTree = sequence;
            if (step.poll() != null) {
                WhileController poll = new WhileController();
                poll.setName("poll:" + step.id());
                poll.setCondition("${oct.poll." + step.id() + ".continue}");
                tag(poll, "org.apache.jmeter.control.gui.WhileControllerGui");
                requestTree = sequence.add(poll);
            }
            if(step.preparation()!=null) {
                for(int i=0;i<=step.preparation().tools().size();i++) {
                    var preparation=new JSR223Sampler();
                    String action=i==step.preparation().tools().size()?"request":step.preparation().tools().get(i).id();
                    preparation.setName("prepare:"+step.id()+":"+action);
                    var preparationPayload=new TreeMap<String,Object>(payload); preparationPayload.put("actionIndex",i);
                    script(preparation,"prepare.groovy",Base64.getEncoder().encodeToString(canonical(preparationPayload).getBytes(StandardCharsets.UTF_8)),"octopus-prepare-v1");
                    tag(preparation,"org.apache.jmeter.testbeans.gui.TestBeanGUI"); requestTree.add(preparation);
                    sourceMap.put(preparation.getName(),ir.flowId()+"@"+ir.flowRevision()+"/"+step.id()+"#"+ir.testPointId()+"/prepare/"+action);
                }
            }
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setName("step:" + step.id());
            sampler.setDomain(uri.getHost());
            sampler.setProtocol(uri.getScheme());
            sampler.setPort(
                    uri.getPort() < 0
                            ? ("https".equals(uri.getScheme()) ? 443 : 80)
                            : uri.getPort());
            sampler.setPath("${oct.path." + step.id() + "}");
            sampler.setMethod(operation.method());
            sampler.setFollowRedirects(false);
            sampler.setAutoRedirects(false);
            long budget=cleanups.containsKey(step.id())?cleanups.get(step.id()).timeoutMillis():35000;
            long connect=Math.min(5000,Math.max(1,budget/4));
            sampler.setConnectTimeout(Long.toString(connect));
            sampler.setResponseTimeout(Long.toString(Math.max(1,budget-connect)));
            boolean body = step.preparation()!=null && step.preparation().body()!=null ||
                    operation.inputs().entrySet().stream()
                            .anyMatch(
                                    p ->
                                            "body".equals(p.getValue().location())
                                                    && step.inputs().containsKey(p.getKey()));
            if (body) {
                sampler.setPostBodyRaw(true);
                sampler.addNonEncodedArgument("", "${oct.body." + step.id() + "}", "");
            }
            tag(sampler, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
            var sampleTree = requestTree.add(sampler);
            if (step.poll() != null) {
                // 首轮 delay=0；之后由 assert 设为轮询间隔
                ConstantTimer pollDelay = new ConstantTimer();
                pollDelay.setName("poll-wait:" + step.id());
                pollDelay.setDelay("${oct.poll." + step.id() + ".delay}");
                tag(pollDelay, "org.apache.jmeter.timers.gui.ConstantTimerGui");
                sampleTree.add(pollDelay);
            }
            HeaderManager headers = new HeaderManager();
            headers.setName("headers:" + step.id());
            tag(headers, "org.apache.jmeter.protocol.http.gui.HeaderPanel");
            if (body) headers.add(new Header("Content-Type", "application/json"));
            for (var port : new TreeMap<>(operation.inputs()).entrySet())
                if ("header".equals(port.getValue().location())
                        && step.inputs().containsKey(port.getKey())
                        && !PreparationChecks.supplies(step.preparation(),port.getValue()))
                    headers.add(
                            new Header(
                                    port.getValue().name(),
                                    "${oct.header." + step.id() + "." + port.getKey() + "}"));
            if(step.preparation()!=null) step.preparation().headers().keySet().forEach(name ->
                    headers.add(new Header(name,"${oct.preparedheader."+step.id()+"."+name+"}")));
            if (headers.size() > 0) sampleTree.add(headers);
            JSR223Assertion assertion = new JSR223Assertion();
            assertion.setName("oracle:" + step.id());
            script(assertion, "assert.groovy", encoded, "octopus-assert-v1");
            tag(assertion, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
            sampleTree.add(assertion);
            sourceMap.put(
                    sampler.getName(),
                    ir.flowId()
                            + "@"
                            + ir.flowRevision()
                            + "/"
                            + step.id()
                            + "#"
                            + ir.testPointId());
            sourceMap.put(guard.getName(), sourceMap.get(sampler.getName()));
            for (var check : DatastoreChecks.of(step)) verification(sequence, ir, step, check, false, sourceMap);
            if (ir.executionSteps() != null) {
                var commit = new JSR223Sampler();
                commit.setName("complete:" + step.id());
                script(commit, "complete.groovy", encode(Map.of("group", callGroups.get(step.id()),
                        "outputs", step.outputs(),"cleanup",cleanups.containsKey(step.id()))), "octopus-complete-v1");
                tag(commit, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
                sequence.add(commit);
                sourceMap.put(commit.getName(), sourceMap.get(sampler.getName()));
            }
        }
        for(var cleanup:cleanups.values()) {
            String id=cleanup.call().id();
            sourceMap.replaceAll((label,value)->value.contains("/"+id+"#")
                    ? value.replace("/"+id+"#",(cleanup.required()?"/cleanup-required/":"/cleanup/")+id+"#") : value);
        }
        // FR-LIN-06: only the fixed scaffolding may appear; While only for steps polling under an authorization.
        var pollSteps = new HashSet<String>();
        for (Step step : ordered) if (step.poll() != null) pollSteps.add(step.id());
        JmxLinearGuard.check(tree, pollSteps, key -> resource(key.replace("octopus-", "").replace("-v1", "") + ".groovy"));
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveService.saveTree(tree, output);
            return new Compilation(
                    output.toString(StandardCharsets.UTF_8),
                    hash(ir),
                    sourceMap,
                    registry.require(ir.requiresCapabilities()));
        } catch (IOException e) {
            throw new IllegalStateException("JMX serialization failed", e);
        }
    }

    private static String encode(Object value) {
        return Base64.getEncoder().encodeToString(canonical(value).getBytes(StandardCharsets.UTF_8));
    }

    public static URI targetUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid service URL");
        }
        require(
                Set.of("http", "https").contains(uri.getScheme())
                        && uri.getHost() != null
                        && uri.getUserInfo() == null
                        && uri.getQuery() == null
                        && uri.getFragment() == null,
                "Service target must be an HTTP(S) base URL without credentials, query or"
                        + " fragment");
        return uri;
    }

    private static void verification(org.apache.jorphan.collections.HashTree sequence, TestIR ir, Step step,
                                     Assertion assertion, boolean capture, Map<String,String> sourceMap) {
        var check = DatastoreChecks.plan(assertion);
        var payload = Map.of("plan", check, "connectionKey", DatastoreChecks.key(check.connection()),
                "markerKey", step.id() + ":" + assertion.id(), "capture", capture);
        var sampler = new JSR223Sampler();
        sampler.setName((capture ? "observe:" : "verify:") + step.id() + ":" + assertion.id());
        script(sampler, "verify.groovy", Base64.getEncoder().encodeToString(canonical(payload).getBytes(StandardCharsets.UTF_8)), "octopus-verify-v1");
        tag(sampler, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        sequence.add(sampler);
        sourceMap.put(sampler.getName(), ir.flowId() + "@" + ir.flowRevision() + "/" + step.id() + "#" + ir.testPointId() + "/" + assertion.id());
    }

    private static synchronized void initialize() {
        if (initialized) return;
        try {
            var directory = Files.createTempDirectory("octopus-jmx-serializer-");
            directory.toFile().deleteOnExit();
            Properties properties = new Properties();
            for (String name : List.of("saveservice.properties", "upgrade.properties")) {
                var file = directory.resolve(name);
                Files.writeString(file, resource("jmeter-5.6.3/" + name));
                file.toFile().deleteOnExit();
                properties.setProperty(name.replace('.', '_'), file.toAbsolutePath().toString());
            }
            var config = directory.resolve("jmeter.properties");
            config.toFile().deleteOnExit();
            try (var out = Files.newOutputStream(config)) {
                properties.store(out, "Octopus JMX compiler");
            }
            if (JMeterUtils.getJMeterProperties() == null) {
                JMeterUtils.setJMeterHome(directory.toString());
                JMeterUtils.loadJMeterProperties(config.toString());
            } else
                properties.forEach(
                        (key, value) -> JMeterUtils.setProperty(key.toString(), value.toString()));
            JMeterUtils.initLocale();
            SaveService.loadProperties();
        } catch (IOException e) {
            throw new IllegalStateException("JMeter serialization resources unavailable", e);
        }
        initialized = true;
    }

    private static String resource(String name) {
        try (var stream = JmxCompiler.class.getResourceAsStream("/testing/" + name)) {
            if (stream == null)
                throw new IllegalStateException("Missing compiler template: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void script(
            TestElement element, String template, String parameters, String cacheKey) {
        // TestBean setters alone do not populate the serializable TestElement property map.
        element.setProperty("scriptLanguage", "groovy");
        element.setProperty("script", resource(template));
        element.setProperty("parameters", parameters);
        element.setProperty("cacheKey", cacheKey);
        element.setProperty("filename", "");
    }

    private static void tag(TestElement element, String gui) {
        element.setProperty(TestElement.TEST_CLASS, element.getClass().getName());
        element.setProperty(TestElement.GUI_CLASS, gui);
        element.setEnabled(true);
    }
}
