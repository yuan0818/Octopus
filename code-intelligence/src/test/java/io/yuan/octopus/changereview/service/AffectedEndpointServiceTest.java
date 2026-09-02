package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.dto.AffectedEndpoint;
import io.yuan.octopus.changereview.dto.ChangedMethod;
import io.yuan.octopus.dto.response.EndpointDTO;
import io.yuan.octopus.service.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AffectedEndpointServiceTest {

    @Mock private Driver neo4jDriver;
    @Mock private QueryService queryService;
    @Mock private Session session;
    @Mock private Result neo4jResult;
    @Mock private Record record;
    @Mock private Value value;

    private AffectedEndpointService service;

    private static final String BRANCH = "main";
    private static final String MERGE = "ghi789";

    @BeforeEach
    void setUp() {
        service = new AffectedEndpointService(neo4jDriver, queryService);
    }

    @Test
    void shouldFindDirectlyAffectedEndpoint() {
        // Changed method IS an endpoint → directly affected
        ChangedMethod cm = new ChangedMethod(10L, "com.Ctrl", "get", "sig_ctrl",
                "/f/Ctrl.java", "ADDED");
        EndpointDTO ep = new EndpointDTO("GET", "/api/orders", "get", "com.Ctrl",
                "Ctrl", "sig_ctrl", 10L, "svc", BRANCH, MERGE);

        when(queryService.getEndpoints(10L, BRANCH, MERGE)).thenReturn(List.of(ep));
        // Neo4j: no upstream callers reachable
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neo4jResult);
        when(neo4jResult.hasNext()).thenReturn(false);

        List<AffectedEndpoint> affected = service.getAffectedEndpoints(BRANCH, MERGE, List.of(cm));
        assertEquals(1, affected.size());
        assertEquals("sig_ctrl", affected.get(0).signature());
        assertEquals(1, affected.get(0).changedMethodsReached().size());
        assertTrue(affected.get(0).changedMethodsReached().contains("sig_ctrl"));
    }

    @Test
    void shouldFindIndirectlyAffectedEndpointViaReverseTraversal() {
        // Changed method `sig_helper` is called by endpoint `sig_ctrl` via reverse traversal
        ChangedMethod cm = new ChangedMethod(10L, "com.Helper", "calc", "sig_helper",
                "/f/Helper.java", "MODIFIED");
        EndpointDTO ep = new EndpointDTO("GET", "/api/orders", "get", "com.Ctrl",
                "Ctrl", "sig_ctrl", 10L, "svc", BRANCH, MERGE);

        when(queryService.getEndpoints(10L, BRANCH, MERGE)).thenReturn(List.of(ep));

        // Neo4j: upstream reachable — `sig_ctrl` calls sig_helper
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neo4jResult);
        when(neo4jResult.hasNext()).thenReturn(true, false);
        when(neo4jResult.next()).thenReturn(record);
        when(record.get("sig")).thenReturn(value);
        when(value.isNull()).thenReturn(false);
        when(value.asString()).thenReturn("sig_ctrl");

        List<AffectedEndpoint> affected = service.getAffectedEndpoints(BRANCH, MERGE, List.of(cm));
        assertEquals(1, affected.size());
        assertEquals("sig_ctrl", affected.get(0).signature());
        assertEquals(1, affected.get(0).changedMethodsReached().size());
        assertTrue(affected.get(0).changedMethodsReached().contains("sig_helper"));
    }

    @Test
    void shouldDeduplicateEndpointReachableFromMultipleChangedMethods() {
        ChangedMethod cm1 = new ChangedMethod(10L, "com.A", "a", "sig_a",
                "/f/A.java", "MODIFIED");
        ChangedMethod cm2 = new ChangedMethod(10L, "com.B", "b", "sig_b",
                "/f/B.java", "MODIFIED");
        EndpointDTO ep = new EndpointDTO("GET", "/api/x", "x", "com.Ctrl",
                "Ctrl", "sig_x", 10L, "svc", BRANCH, MERGE);

        when(queryService.getEndpoints(10L, BRANCH, MERGE)).thenReturn(List.of(ep));
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neo4jResult);
        // Both changed methods reach ep sig_x
        when(neo4jResult.hasNext()).thenReturn(true, false, true, false);
        when(neo4jResult.next()).thenReturn(record);
        when(record.get("sig")).thenReturn(value);
        when(value.isNull()).thenReturn(false);
        when(value.asString()).thenReturn("sig_x");

        List<AffectedEndpoint> affected = service.getAffectedEndpoints(BRANCH, MERGE, List.of(cm1, cm2));
        assertEquals(1, affected.size()); // deduped
        assertEquals(2, affected.get(0).changedMethodsReached().size()); // both recorded
        assertTrue(affected.get(0).changedMethodsReached().contains("sig_a"));
        assertTrue(affected.get(0).changedMethodsReached().contains("sig_b"));
    }

    @Test
    void shouldFilterOutDeletedMethodsAsSeeds() {
        ChangedMethod deleted = new ChangedMethod(10L, "com.Old", "old", "sig_old",
                "/f/Old.java", "DELETED");
        ChangedMethod added = new ChangedMethod(10L, "com.New", "n", "sig_new",
                "/f/New.java", "ADDED");
        EndpointDTO ep = new EndpointDTO("GET", "/api", "x", "com.Ctrl", "Ctrl",
                "sig_x", 10L, "svc", BRANCH, MERGE);

        when(queryService.getEndpoints(10L, BRANCH, MERGE)).thenReturn(List.of(ep));
        // Only the ADDED method's signature is passed to Neo4j; DELETED is filtered
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neo4jResult);
        when(neo4jResult.hasNext()).thenReturn(false); // sig_new has no upstream callers

        List<AffectedEndpoint> affected = service.getAffectedEndpoints(BRANCH, MERGE,
                List.of(deleted, added));
        // sig_new is NOT an endpoint and has no callers → 0 affected
        assertTrue(affected.isEmpty());
    }

    @Test
    void shouldHandleNoEndpoints() {
        ChangedMethod cm = new ChangedMethod(10L, "com.A", "a", "sig_a",
                "/f/A.java", "MODIFIED");
        when(queryService.getEndpoints(10L, BRANCH, MERGE)).thenReturn(List.of());

        List<AffectedEndpoint> affected = service.getAffectedEndpoints(BRANCH, MERGE, List.of(cm));
        assertTrue(affected.isEmpty());
    }

    @Test
    void shouldHandleEmptyChangedMethods() {
        List<AffectedEndpoint> affected = service.getAffectedEndpoints(BRANCH, MERGE, List.of());
        assertTrue(affected.isEmpty());
    }

    @Test
    void shouldGroupByProjectId() {
        ChangedMethod cm1 = new ChangedMethod(10L, "com.A", "a", "sig_a",
                "/f/A.java", "MODIFIED");
        ChangedMethod cm2 = new ChangedMethod(20L, "com.B", "b", "sig_b",
                "/f/B.java", "MODIFIED");
        EndpointDTO ep1 = new EndpointDTO("GET", "/api/a", "a", "com.A", "A",
                "sig_a", 10L, "svc1", BRANCH, MERGE);
        EndpointDTO ep2 = new EndpointDTO("GET", "/api/b", "b", "com.B", "B",
                "sig_b", 20L, "svc2", BRANCH, MERGE);

        when(queryService.getEndpoints(10L, BRANCH, MERGE)).thenReturn(List.of(ep1));
        when(queryService.getEndpoints(20L, BRANCH, MERGE)).thenReturn(List.of(ep2));
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neo4jResult);
        when(neo4jResult.hasNext()).thenReturn(false); // no upstream callers

        List<AffectedEndpoint> affected = service.getAffectedEndpoints(BRANCH, MERGE,
                List.of(cm1, cm2));
        assertEquals(2, affected.size()); // one per project, both directly affected
    }
}
