package io.yuan.octopus.scenario.testing;
import io.yuan.octopus.preparation.*;
import static io.yuan.octopus.preparation.PreparationPlan.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PreparationValidationTest {
    final CapabilityRegistry registry=new CapabilityRegistry();
    final IrValidator validator=new IrValidator(registry);
    @Test void validatesAndCompilesOrderedActionsWithGatewayHeadersWithoutChangingApiContract() {
        var ir=PreparationFixtures.ir(PreparationFixtures.plan("MD5"));
        assertEquals(List.of("login","verify"),validator.validate(ir).stream().map(TestingModel.Step::id).toList());
        var compilation=new JmxCompiler(validator,registry).compile(ir,new TestingModel.ExecutionProfile(Map.of("account.auth",new TestingModel.Target("http://localhost")),Map.of(),Map.of()));
        assertEquals(6,compilation.sourceMap().keySet().stream().filter(s -> s.startsWith("prepare:")).count());
        assertTrue(compilation.jmx().contains("oct.preparedheader.login.sign"));
        assertFalse(TestingFixtures.login().inputs().containsKey("sign"));
    }
    @Test void rejectsForwardReferencesMismatchedWireDataUnknownToolsAndLiteralCredentials() {
        var p=PreparationFixtures.plan("MD5");
        var calls=new ArrayList<>(p.tools()); var encrypt=calls.get(3);
        var values=new HashMap<>(encrypt.inputs()); values.put("publicKey",PreparationFixtures.literal("DO_NOT_ACCEPT_PUBLIC_KEY_LITERAL"));
        calls.set(3,new Call(encrypt.id(),encrypt.tool(),encrypt.version(),values));
        assertThrows(IllegalArgumentException.class,()->validator.validate(PreparationFixtures.ir(new PreparationPlan(calls,p.body(),p.headers()))));
        assertThrows(IllegalArgumentException.class,()->validator.validate(PreparationFixtures.ir(new PreparationPlan(p.tools(),new Value("request","body"),p.headers()))));
        var reversed=new ArrayList<>(p.tools()); Collections.swap(reversed,0,1);
        assertThrows(IllegalArgumentException.class,()->validator.validate(PreparationFixtures.ir(new PreparationPlan(reversed,p.body(),p.headers()))));
        assertThrows(IllegalArgumentException.class,()->validator.validate(PreparationFixtures.ir(new PreparationPlan(List.of(new Call("bad","groovy","1",Map.of())),null,Map.of()))));
    }
    @Test void legacyFlowHashesRemainUnchangedAndNewPlansAreLocked() {
        var businessPayload=new LinkedHashMap<String,Object>();businessPayload.put("operationRef","example");businessPayload.put("inputs",Map.of());businessPayload.put("preparation",null);
        assertTrue(TestingJson.canonical(businessPayload).contains("\"preparation\":null"));
        var legacy=TestingFixtures.flow(); var json=TestingJson.GSON.toJsonTree(legacy).getAsJsonObject();
        json.getAsJsonArray("nodes").forEach(n -> n.getAsJsonObject().remove("preparation"));
        assertEquals(TestingJson.hash(json),TestingJson.hash(legacy));
        assertNotEquals(TestingJson.hash(TestingFixtures.ir()),TestingJson.hash(PreparationFixtures.ir(PreparationFixtures.plan("MD5"))));
    }
}
