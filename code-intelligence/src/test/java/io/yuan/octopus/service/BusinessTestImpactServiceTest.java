package io.yuan.octopus.service;

import static io.yuan.octopus.service.BusinessTestImpactService.*;

import static org.junit.jupiter.api.Assertions.*;

import io.yuan.octopus.dto.response.EndpointDTO;

import org.junit.jupiter.api.Test;

import java.util.*;

class BusinessTestImpactServiceTest {
    @Test
    void findsCrossModuleEndpointWithFullWitnessPathAndTerminatesCycles() {
        var endpoint = new MethodRef(1L, "main", "base", "Controller.refund()");
        var service = new MethodRef(2L, "main", "base", "Payment.refund()");
        var changed = new MethodRef(3L, "main", "base", "State.check()");
        var first = new CallEvidence(endpoint, service, "DIRECT", 12);
        var second = new CallEvidence(service, changed, "DIRECT", 25);
        Map<String, List<CallEvidence>> callers =
                Map.of(
                        "3:State.check()",
                        List.of(second),
                        "2:Payment.refund()",
                        List.of(first),
                        "1:Controller.refund()",
                        List.of(new CallEvidence(changed, endpoint, "DIRECT", 35)));
        EndpointDTO api =
                new EndpointDTO(
                        "POST",
                        "/refund",
                        "refund",
                        "Controller",
                        "Controller",
                        endpoint.signature(),
                        1L,
                        "api",
                        "main",
                        "base");
        var result =
                BusinessTestImpactService.trace(
                        new Seed(changed, "DELETE", "State.java", List.of(new int[] {3, 3})),
                        "BASE",
                        callers,
                        Map.of("1:Controller.refund()", List.of(api)));
        assertEquals(1, result.size());
        assertEquals(List.of(first, second), result.getFirst().path());
        assertEquals("BASE", result.getFirst().side());
    }
}
