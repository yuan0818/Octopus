package io.yuan.octopus.scenario.testing;
import io.yuan.octopus.preparation.PreparationPlan;
import static io.yuan.octopus.preparation.PreparationPlan.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import java.util.*;

final class PreparationFixtures {
    static Value literal(Object v) { return new Value("literal",v); }
    static Value result(String v) { return new Value("result",v); }
    static PreparationPlan plan(String algorithm) {
        var timestamp=result("timestamp.value"); var text=result("timestampText.value"); var id=result("requestId.value");
        var appid=literal("fixture-app"); var body=result("encrypt.body");
        return new PreparationPlan(List.of(
            new Call("timestamp","time.now","1",Map.of()),
            new Call("timestampText","string.value","1",Map.of("value",timestamp)),
            new Call("requestId","uuid","1",Map.of()),
            new Call("encrypt","platform.encrypt","1",Map.of("content",new Value("input","body"),"publicKey",new Value("context","rsa.public"),"keyPairId",literal("fixture-key"),"timestamp",timestamp,"version",literal(1))),
            new Call("sign","platform.sign","1",Map.of("body",body,"query",new Value("request","query"),"appid",appid,"requestid",id,"timestamp",text,"algorithm",literal(algorithm),"signingSecret",new Value("context","signing.secret")))
        ),body,Map.of("appid",appid,"requestid",id,"timestamp",text,"sign",result("sign.sign")));
    }
    static TestIR ir(PreparationPlan plan) {
        var old=TestingFixtures.ir();
        var login=TestingFixtures.login(); var ports=new LinkedHashMap<>(login.inputs());
        ports.put("lookup",new Port("query","lookup","string",true,null));
        var withQuery=new Operation(login.operationId(),login.revision(),login.system(),login.service(),login.method(),login.path(),ports,login.requestSchema(),login.responseSchema(),login.implementations(),login.source(),login.requiresCapabilities(),login.securityProfile());
        var context=new LinkedHashMap<>(old.initialContext()); context.put("rsa.public",new Variable("string",true)); context.put("signing.secret",new Variable("string",true));
        return new TestIR(old.irVersion(),old.scenarioId(),old.flowId(),old.flowRevision(),old.flowHash(),old.testPointId(),old.oracleHash(),context,List.of(withQuery,TestingFixtures.verify()),old.stateModels(),
            old.steps().stream().map(s -> {
                if(!s.id().equals("login")) return s;
                var inputs=new LinkedHashMap<>(s.inputs()); inputs.put("lookup",new Binding("literal","中文+A %25&timestamp=0"));
                return new Step(s.id(),s.operationRef(),inputs,s.outputs(),s.states(),s.assertions(),s.waitMillis(),plan);
            }).toList(),
            old.dependencies(),List.of("http","json","request.prepare"),old.intentionallyViolatedRule(),old.changeEvidenceRefs());
    }
}
