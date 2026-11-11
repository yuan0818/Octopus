package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@EnabledIfEnvironmentVariable(named="JMETER_HOME",matches=".+")
class OrderedJmeterIntegrationTest {
    @TempDir Path directory;
    HttpServer server;
    ExecutorService executor;
    AtomicInteger active=new AtomicInteger(), maximum=new AtomicInteger(), completed=new AtomicInteger(), nextCalls=new AtomicInteger();
    Set<String> received=ConcurrentHashMap.newKeySet();
    volatile boolean fail, cleanupEnabled, cleanupFails;
    AtomicInteger cleanupCalls=new AtomicInteger();
    AtomicReference<String> cleanedResource=new AtomicReference<>();
    AtomicReference<String> downstreamQuery=new AtomicReference<>();
    Map<String,String> authorization=new ConcurrentHashMap<>();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/call",e -> {
            int inFlight=active.incrementAndGet(); maximum.accumulateAndGet(inFlight,Math::max);
            String key=e.getRequestURI().getQuery(); received.add(key);
            if(e.getRequestHeaders().getFirst("Authorization")!=null) authorization.put(key,e.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(fail && "id=0".equals(key) ? 10 : 250); } catch(InterruptedException x) { Thread.currentThread().interrupt(); }
            byte[] bytes=("{\"value\":\""+key+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            active.decrementAndGet(); completed.incrementAndGet();
            e.sendResponseHeaders(fail && "id=0".equals(key) ? 500 : 200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.createContext("/next",e -> {
            nextCalls.incrementAndGet();
            downstreamQuery.set(e.getRequestURI().getQuery());
            byte[] bytes=("{\"completed\":"+completed.get()+",\"active\":"+active.get()+"}").getBytes();
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.createContext("/cleanup",e->{
            cleanupCalls.incrementAndGet();cleanedResource.set(e.getRequestURI().getRawQuery());
            byte[] bytes="{}".getBytes();e.sendResponseHeaders(cleanupFails?500:200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0);executor.shutdownNow(); }
    TestIR ir(String mode,int count) {
        var op=new Operation("query","v1","fixture","api","GET","/call",Map.of("id",new Port("query","id","string",true,null)),
                Map.of(),Map.of(),List.of(),new Source("HUMAN","fixture","v1"),List.of("http","json"),null);
        var end=new Operation("next","v1","fixture","api","GET","/next",Map.of(),Map.of(),Map.of(),List.of(),
                new Source("HUMAN","fixture","v1"),List.of("http","json"),null);
        List<Step> calls=new ArrayList<>();List<String> ids=new ArrayList<>();
        for(int i=0;i<count;i++) {
            String id="c"+i;ids.add(id);
            calls.add(new Step(id,op.ref(),Map.of("id",new Binding("literal",""+i)),List.of(new Output("checks."+id+".value","$.value","string",false)),
                    List.of(),List.of(new Assertion("status","STATUS_EQUALS",null,200),new Assertion("value","JSON_EQUALS","$.value","id="+i)),0));
        }
        calls.add(new Step("after",end.ref(),Map.of(),List.of(),List.of(),List.of(new Assertion("status","STATUS_EQUALS",null,200),
                new Assertion("barrier","JSON_EQUALS","$.completed",count),new Assertion("idle","JSON_EQUALS","$.active",0)),0));
        var cleanupOp=new Operation("cleanup","v1","fixture","api","DELETE","/cleanup",Map.of("id",new Port("query","owned","string",true,null)),
                Map.of(),Map.of("200",Map.of("type","object")),List.of(),new Source("HUMAN","fixture","v1"),List.of("http","json"),null);
        String resource="checks.c1.value";
        var cleanupCall=new Step("release",cleanupOp.ref(),Map.of("id",new Binding("context",resource)),List.of(),List.of(),List.of(new Assertion("status","STATUS_EQUALS",null,200)),0);
        return new TestIR("0.2","fixture","flow",1,"flow-hash","test","oracle-hash",Map.of(),cleanupEnabled?List.of(op,end,cleanupOp):List.of(op,end),List.of(),calls,List.of(),
                List.of("http","json","flow.ordered"),null,List.of(),List.of(new CallGroup("checks",mode,ids),new CallGroup("finish","SERIAL",List.of("after"))),cleanupEnabled?List.of(new CleanupStep(cleanupCall,resource,List.of("PASS","FAIL","INCONCLUSIVE"),true,3000)):null);
    }
    JmeterProcessExecutor.Result run(String mode,int count) throws Exception {
        var profile=new ExecutionProfile(Map.of("fixture.api",new Target("http://127.0.0.1:"+server.getAddress().getPort())),Map.of(),Map.of());
        return execute(ir(mode,count),profile);
    }
    JmeterProcessExecutor.Result execute(TestIR ir,ExecutionProfile profile) throws Exception {
        var registry=new CapabilityRegistry();var compiler=new JmxCompiler(new IrValidator(registry),registry);
        var result=new JmeterProcessExecutor().execute(Path.of(System.getenv("JMETER_HOME")),directory,compiler.compile(ir,profile),profile,60,new AtomicBoolean());
        return result;
    }
    @Test void parallelEnvironmentAndAuthAreIsolatedAndBothOutputsReachTheNextStep() throws Exception {
        var base=ir("PARALLEL",2);var op=base.operations().getFirst();var end=base.operations().get(1);
        var inputs=new LinkedHashMap<>(op.inputs());inputs.put("auth",new Port("header","Authorization","string",true,"Bearer "));
        var query=new Operation(op.operationId(),op.revision(),op.system(),op.service(),op.method(),op.path(),inputs,op.requestSchema(),op.responseSchema(),op.implementations(),op.source(),op.requiresCapabilities(),null);
        var after=new Operation(end.operationId(),end.revision(),end.system(),end.service(),end.method(),end.path(),Map.of("left",new Port("query","left","string",true,null),"right",new Port("query","right","string",true,null)),end.requestSchema(),end.responseSchema(),end.implementations(),end.source(),end.requiresCapabilities(),null);
        var calls=base.steps().stream().map(c->new Step(c.id(),c.operationRef(),c.id().equals("after")?
                Map.of("left",new Binding("context","checks.c0.value"),"right",new Binding("context","checks.c1.value")):
                Map.of("id",new Binding("environment","tenant"),"auth",new Binding("environment","token")),c.outputs(),c.states(),c.assertions(),0)).toList();
        var test=new TestIR("0.2",base.scenarioId(),base.flowId(),1,base.flowHash(),base.testPointId(),base.oracleHash(),Map.of(),List.of(query,after),List.of(),calls,List.of(),base.requiresCapabilities(),null,List.of(),base.executionSteps());
        var environments=Map.of("c0",new CallEnvironment(Map.of("id","0","auth","token-zero"),Map.of(),"s0"),"c1",new CallEnvironment(Map.of("id","1","auth","token-one"),Map.of(),"s1"));
        var profile=new ExecutionProfile(Map.of("fixture.api",new Target("http://127.0.0.1:"+server.getAddress().getPort())),Map.of(),Map.of(),"test",environments);
        assertEquals("PASS",execute(test,profile).verdict());
        assertEquals(Map.of("id=0","Bearer token-zero","id=1","Bearer token-one"),authorization);
        assertTrue(downstreamQuery.get().contains("left=id=0"));assertTrue(downstreamQuery.get().contains("right=id=1"));
        assertFalse(Files.exists(directory.resolve("environment.json")));
        assertFalse(Files.readString(directory.resolve("plan.jmx")).contains("token-zero"));
    }
    @Test void stepComparisonRunsAtTheBarrierAndBlocksTheNextStep() throws Exception {
        var base=ir("PARALLEL",2);var group=base.executionSteps().getFirst();
        var test=new TestIR("0.2",base.scenarioId(),base.flowId(),1,base.flowHash(),base.testPointId(),base.oracleHash(),Map.of(),base.operations(),List.of(),base.steps(),List.of(),base.requiresCapabilities(),null,List.of(),
                List.of(new CallGroup(group.id(),group.mode(),group.callIds(),List.of(new GroupAssertion("compare","checks.c0.value","checks.c1.value"))),base.executionSteps().getLast()));
        var result=execute(test,new ExecutionProfile(Map.of("fixture.api",new Target("http://127.0.0.1:"+server.getAddress().getPort())),Map.of(),Map.of()));
        assertEquals("FAIL",result.verdict());assertEquals(0,nextCalls.get());
        assertTrue(result.samples().stream().anyMatch(s->s.responseCode().equals("FLOW_ASSERTION_FAILED")));
    }
    @Test void realParallelCallsAreBoundedAndNextStepWaitsForAllResults() throws Exception {
        var result=run("PARALLEL",9);
        assertEquals("PASS",result.verdict());assertTrue(maximum.get()>1);assertTrue(maximum.get()<=4);
        assertEquals(9,received.size());assertEquals(1,nextCalls.get());
        assertEquals(10,result.calls().size());assertTrue(result.calls().stream().allMatch(c->c.status().equals("PASS")));
        assertEquals(10,result.samples().stream().filter(s->s.label().startsWith("step:")).count());
    }
    @Test void knownResourcesAreCleanedAfterAnotherParallelCallFails() throws Exception {
        cleanupEnabled=true;fail=true;var result=run("PARALLEL",12);
        assertEquals("FAIL",result.verdict());assertEquals("PASS",result.cleanupStatus());
        assertEquals("BLOCKED",result.calls().stream().filter(c->c.callId().equals("after")).findFirst().orElseThrow().status());
        assertEquals(1,cleanupCalls.get());assertEquals("owned=id%3D1",cleanedResource.get());assertFalse(result.gatePassed());
    }
    @Test void cleanupFailureDoesNotRewriteBusinessPassButFailsTheGate() throws Exception {
        cleanupEnabled=true;cleanupFails=true;var result=run("PARALLEL",3);
        assertEquals("PASS",result.verdict());assertEquals("FAIL",result.cleanupStatus());assertFalse(result.gatePassed());
    }
    @Test void successfulBusinessAndRequiredCleanupPassTheGate() throws Exception {
        cleanupEnabled=true;var result=run("PARALLEL",3);
        assertEquals("PASS",result.verdict());assertEquals("PASS",result.cleanupStatus());assertTrue(result.gatePassed());
    }
    @Test void serialGroupPreservesSingleInFlightCall() throws Exception {
        assertEquals("PASS",run("SERIAL",3).verdict());assertEquals(1,maximum.get());
    }
    @Test void failureStopsQueuedCallsAndBlocksFollowingStep() throws Exception {
        fail=true;var result=run("PARALLEL",12);
        assertEquals("FAIL",result.verdict());assertTrue(received.size()<=4);assertEquals(0,nextCalls.get());
    }
}
