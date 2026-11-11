package io.yuan.octopus.scenario.testing;
import static io.yuan.octopus.scenario.testing.TestingModel.*;
import static io.yuan.octopus.scenario.testing.ProjectContextService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EnvironmentBindingsTest {
    @Test void sameVariableNameResolvesIndependentlyForEachCallAndDoesNotOverwriteContext() {
        var contexts=mock(ProjectContextService.class);var catalog=mock(RepositoryCatalogClient.class);
        var base=TestingFixtures.ir();
        var steps=base.steps().stream().map(s->new Step(s.id(),s.operationRef(),Map.of(s.id().equals("login")?"body":"token",
                new Binding("environment",Map.of("key","shared","repositoryKey","repo","modulePath",s.id()))),s.outputs(),s.states(),s.assertions(),0)).toList();
        var values=Map.<String,Object>of("login",Map.of("user","fixture"),"verify","module-token");
        for(var entry:values.entrySet()) when(contexts.get("repo",entry.getKey(),"test")).thenReturn(new Resolved(null,
                new Definition("","","",List.of(),List.of(new EnvironmentVariable("shared",entry.getValue() instanceof Map?"object":"string",entry.getValue()))),
                Map.of(),false,true,Map.of("shared","模块配置")));
        var ir=new TestIR(base.irVersion(),base.scenarioId(),base.flowId(),base.flowRevision(),base.flowHash(),base.testPointId(),base.oracleHash(),base.initialContext(),base.operations(),
                base.stateModels(),steps,base.dependencies(),base.requiresCapabilities(),null,List.of());
        var profile=new ExecutionProfile(Map.of(),Map.of("unrelated","preserved"),Map.of(),"test",
                Map.of("verify",new CallEnvironment(Map.of("token","forged"),Map.of(),"forged")));
        var resolved=new EnvironmentBindings(contexts,catalog).resolve(ir,profile);
        assertEquals("module-token",resolved.callEnvironments().get("verify").inputs().get("token"));
        assertEquals(Map.of("user","fixture"),resolved.callEnvironments().get("login").inputs().get("body"));
        assertEquals(profile.initialContext(),resolved.initialContext());
        verify(contexts,never()).get("repo","verify","production");
    }
    @Test void explicitScopeCannotBePartiallySpecified() {
        assertThrows(IllegalArgumentException.class,()->EnvironmentBindings.reference(Map.of("key","tenant","modulePath","other")));
        assertThrows(IllegalArgumentException.class,()->EnvironmentBindings.reference(Map.of("key","tenant","script","no")));
    }
}
