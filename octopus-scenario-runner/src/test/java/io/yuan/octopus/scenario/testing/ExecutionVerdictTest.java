package io.yuan.octopus.scenario.testing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;

class ExecutionVerdictTest {
    @Test void cleanupOutsideItsTerminalPolicyDoesNotFailTheBusinessGate() {
        var compilation=new TestingModel.Compilation("", "hash",Map.of("step:business","f@1/business#p","step:cleanup","f@1/cleanup-required/cleanup#p"),List.of());
        var samples=List.of(new JmeterProcessExecutor.Sample("step:business",true,"200",1,""),
                new JmeterProcessExecutor.Sample("dispatch:cleanup:cleanup",true,"CLEANUP_NOT_APPLICABLE",1,""));
        var result=JmeterProcessExecutor.report("COMPLETED",samples,compilation,null);
        assertEquals("PASS",result.verdict());assertEquals("NOT_APPLICABLE",result.cleanupStatus());assertTrue(result.gatePassed());
        var missing=JmeterProcessExecutor.report("COMPLETED",List.of(samples.getFirst()),compilation,null);
        assertEquals("UNKNOWN",missing.cleanupStatus());assertFalse(missing.gatePassed());
    }
    @Test void laterInfrastructureInterruptionCannotEraseBusinessFailure() {
        var failed=new JmeterProcessExecutor.Sample("step:check",false,"200",5,"Business verification failed: value");
        var error=new JmeterProcessExecutor.Sample("verify:next",false,"VERIFY_ERROR",5,"");
        assertEquals("FAIL",JmeterProcessExecutor.verdict(List.of(failed,error),false));
        assertEquals("INCONCLUSIVE",JmeterProcessExecutor.verdict(List.of(error),false));
        assertEquals("INCONCLUSIVE",JmeterProcessExecutor.verdict(List.of(new JmeterProcessExecutor.Sample("step:x",false,"Non HTTP response code: timeout",5,"Business verification failed: status")),false));
    }
}
