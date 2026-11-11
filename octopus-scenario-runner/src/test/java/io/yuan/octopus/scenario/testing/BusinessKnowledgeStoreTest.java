package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.neo4j.harness.*;

import java.util.*;

class BusinessKnowledgeStoreTest {
    @Test
    void persistsIndependentBusinessGraphWithImmutableReviewAndDeterministicScenario() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
                Driver driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none())) {
            var store = new BusinessKnowledgeStore(driver);
            store.saveOperation(TestingFixtures.login());
            store.saveOperation(TestingFixtures.verify());
            var planner = new ScenarioPlanner(store, new IrValidator(new CapabilityRegistry()));
            planner.validateFlow(TestingFixtures.flow());
            var draft = store.saveDraft(TestingFixtures.flow());
            assertEquals("DRAFT", draft.status());
            assertEquals(1, draft.definition().revision());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> planner.generate("twofa", 1, "normal", List.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.decide("twofa", 1, "stale-hash", "reviewer", true));
            var confirmed = store.decide("twofa", 1, draft.hash(), "reviewer", true);
            assertEquals("CONFIRMED", confirmed.status());
            var point =
                    new BusinessTestImpactBridge.AffectedPoint(
                            "twofa",
                            1,
                            "normal",
                            List.of("verify"),
                            List.of("login"),
                            List.of("evidence-1"),
                            "fixture witness");
            var report =
                    new BusinessTestImpactBridge.Impact(
                            Map.of("coverage", "STATIC_GRAPH"), List.of(point), List.of());
            String evidenceRef = store.saveImpact(report) + ":evidence-1";
            assertThrows(
                    IllegalArgumentException.class,
                    () -> planner.generate("twofa", 1, "normal", List.of("forged-evidence")));
            var ir = planner.generate("twofa", 1, "normal", List.of(evidenceRef));
            assertEquals(confirmed.hash(), ir.flowHash());
            assertEquals(List.of(evidenceRef), ir.changeEvidenceRefs());
            assertEquals(1, store.evidenceSnapshots(ir.changeEvidenceRefs()).size());
            assertThrows(
                    IllegalArgumentException.class, () -> store.saveDraft(TestingFixtures.flow()));
            try (var session = driver.session()) {
                assertEquals(
                        2,
                        session.run(
                                        "MATCH (:BusinessFlow"
                                            + " {flowId:'twofa',revision:1})-[:CONTAINS]->(n:FlowNode)"
                                            + " RETURN count(n) AS count")
                                .single()
                                .get("count")
                                .asInt());
                assertEquals(
                        1,
                        session.run(
                                        "MATCH (:FlowNode"
                                            + " {nodeId:'login'})-[:PRODUCES]->(v:ContextVariable)<-[:REQUIRES]-(:FlowNode"
                                            + " {nodeId:'verify'}) RETURN count(v) AS count")
                                .single()
                                .get("count")
                                .asInt());
                assertEquals(
                        0,
                        session.run("MATCH (:FlowNode)-[r:CALLS]->() RETURN count(r) AS count")
                                .single()
                                .get("count")
                                .asInt());
            }
        }
    }
}
