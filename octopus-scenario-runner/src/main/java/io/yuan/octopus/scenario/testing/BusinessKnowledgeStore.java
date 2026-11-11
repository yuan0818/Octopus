package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingJson.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

import org.neo4j.driver.Driver;
import org.neo4j.driver.TransactionContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

/** Canonical definitions and their graph projection are written in the same Neo4j transaction. */
@Repository
public class BusinessKnowledgeStore {
    private final Driver driver;
    private volatile boolean initialized;

    public BusinessKnowledgeStore(Driver driver) {
        this.driver = driver;
    }

    private synchronized void initialize() {
        if (initialized) return;
        try (var session = driver.session()) {
            for (String statement :
                    List.of(
                            "CREATE CONSTRAINT bt_impact IF NOT EXISTS FOR (n:BusinessImpactReport)"
                                    + " REQUIRE n.hash IS UNIQUE",
                            "CREATE CONSTRAINT bt_operation IF NOT EXISTS FOR (n:ApiOperation)"
                                    + " REQUIRE (n.operationId,n.revision) IS UNIQUE",
                            "CREATE CONSTRAINT bt_flow IF NOT EXISTS FOR (n:BusinessFlow) REQUIRE"
                                    + " (n.flowId,n.revision) IS UNIQUE",
                            "CREATE CONSTRAINT bt_flow_node IF NOT EXISTS FOR (n:FlowNode) REQUIRE"
                                    + " (n.flowId,n.flowRevision,n.nodeId) IS UNIQUE",
                            "CREATE CONSTRAINT bt_context IF NOT EXISTS FOR (n:ContextVariable)"
                                    + " REQUIRE (n.flowId,n.flowRevision,n.key) IS UNIQUE",
                            "CREATE CONSTRAINT bt_state IF NOT EXISTS FOR (n:StateModel) REQUIRE"
                                    + " (n.modelId,n.revision) IS UNIQUE",
                            "CREATE CONSTRAINT bt_point IF NOT EXISTS FOR (n:TestPoint) REQUIRE"
                                    + " (n.flowId,n.flowRevision,n.pointId) IS UNIQUE"))
                session.run(statement).consume();
        }
        initialized = true;
    }

    public String saveImpact(BusinessTestImpactBridge.Impact impact) {
        initialize();
        String digest = hash(impact);
        try (var session = driver.session()) {
            session.executeWrite(
                    tx -> {
                        tx.run(
                                        "MERGE (r:BusinessImpactReport {hash:$hash}) ON CREATE SET"
                                                + " r.definition=$json,r.createdAt=$at",
                                        Map.of(
                                                "hash",
                                                digest,
                                                "json",
                                                canonical(impact),
                                                "at",
                                                Instant.now().toString()))
                                .consume();
                        return null;
                    });
        }
        return digest;
    }

    public Map<String, BusinessTestImpactBridge.Impact> evidenceSnapshots(List<String> refs) {
        if (refs == null || refs.isEmpty()) return Map.of();
        initialize();
        Map<String, BusinessTestImpactBridge.Impact> reports = new TreeMap<>();
        for (String ref : refs) {
            require(
                    ref != null && ref.matches("[a-f0-9]{64}:[A-Za-z0-9-]+"),
                    "Invalid impact evidence reference");
            String digest = ref.substring(0, 64);
            if (!reports.containsKey(digest))
                try (var session = driver.session()) {
                    var rows =
                            session.run(
                                            "MATCH (r:BusinessImpactReport {hash:$hash}) RETURN"
                                                    + " r.definition AS json",
                                            Map.of("hash", digest))
                                    .list();
                    require(rows.size() == 1, "Impact evidence snapshot unavailable");
                    var report =
                            read(
                                    rows.getFirst().get("json").asString(),
                                    BusinessTestImpactBridge.Impact.class);
                    require(digest.equals(hash(report)), "Impact evidence integrity failure");
                    reports.put(digest, report);
                }
        }
        return reports;
    }

    public void validateEvidence(String flow, int revision, String point, List<String> refs) {
        if (refs == null || refs.isEmpty()) return;
        var snapshots = evidenceSnapshots(refs);
        for (String ref : refs) {
            var report = snapshots.get(ref.substring(0, 64));
            String evidence = ref.substring(65);
            require(
                    report.testPoints().stream()
                            .anyMatch(
                                    p ->
                                            p.flowId().equals(flow)
                                                    && p.flowRevision() == revision
                                                    && p.testPointId().equals(point)
                                                    && p.evidenceRefs().contains(evidence)),
                    "Evidence does not affect this confirmed test point");
        }
    }

    public List<Operation> operations() {
        initialize();
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:ApiOperation) RETURN o.definition AS json ORDER BY"
                                    + " o.operationId,o.revision")
                    .list(r -> GSON.fromJson(r.get("json").asString(), Operation.class));
        }
    }

    public Operation operation(String ref) {
        int at = ref == null ? -1 : ref.lastIndexOf('@');
        require(at > 0, "Operation reference must include revision");
        initialize();
        try (var session = driver.session()) {
            var rows =
                    session.run(
                                    "MATCH (o:ApiOperation {operationId:$id,revision:$rev}) RETURN"
                                            + " o.definition AS json",
                                    Map.of(
                                            "id",
                                            ref.substring(0, at),
                                            "rev",
                                            ref.substring(at + 1)))
                            .list();
            require(!rows.isEmpty(), "Unknown operation: " + ref);
            return GSON.fromJson(rows.getFirst().get("json").asString(), Operation.class);
        }
    }

    public Operation saveOperation(Operation operation) {
        initialize();
        try (var session = driver.session()) {
            return session.executeWrite(
                    tx -> {
                        var params =
                                new HashMap<String, Object>(
                                        Map.of(
                                                "id",
                                                operation.operationId(),
                                                "rev",
                                                operation.revision(),
                                                "json",
                                                canonical(operation)));
                        var row =
                                tx.run(
                                                """
                                                MERGE (o:ApiOperation {operationId:$id,revision:$rev})
                                                ON CREATE SET o.definition=$json
                                                SET o.writeLock=coalesce(o.writeLock,0)+1 RETURN o.definition AS json
                                                """,
                                                params)
                                        .single();
                        Operation existing =
                                GSON.fromJson(row.get("json").asString(), Operation.class);
                        var existingJson = GSON.toJsonTree(existing).getAsJsonObject();
                        existingJson.remove("implementations");
                        var incomingJson = GSON.toJsonTree(operation).getAsJsonObject();
                        incomingJson.remove("implementations");
                        require(
                                canonical(existingJson).equals(canonical(incomingJson)),
                                "Operation revision is immutable; import a new revision");
                        List<Implementation> implementations =
                                new ArrayList<>(existing.implementations());
                        operation
                                .implementations()
                                .forEach(
                                        i -> {
                                            if (!implementations.contains(i))
                                                implementations.add(i);
                                        });
                        implementations.sort(Comparator.comparing(TestingJson::canonical));
                        Operation merged =
                                new Operation(
                                        operation.operationId(),
                                        operation.revision(),
                                        operation.system(),
                                        operation.service(),
                                        operation.method(),
                                        operation.path(),
                                        operation.inputs(),
                                        operation.requestSchema(),
                                        operation.responseSchema(),
                                        implementations,
                                        operation.source(),
                                        operation.requiresCapabilities(),
                                        operation.securityProfile());
                        params.put("json", canonical(merged));
                        params.put("service", operation.service());
                        params.put("system", operation.system());
                        tx.run(
                                        "MATCH (o:ApiOperation {operationId:$id,revision:$rev}) SET"
                                            + " o.definition=$json,o.service=$service,o.system=$system",
                                        params)
                                .consume();
                        projectImplementations(tx, merged);
                        return merged;
                    });
        }
    }

    private static void projectImplementations(TransactionContext tx, Operation operation) {
        for (var impl : operation.implementations()) {
            tx.run(
                            """
                            MATCH (o:ApiOperation {operationId:$id,revision:$rev})
                            MATCH (m:MethodNode {projectId:$pid,branch:$branch,commitId:$commit,signature:$sig})
                            MERGE (o)-[:IMPLEMENTED_BY]->(m)
                            """,
                            Map.of(
                                    "id",
                                    operation.operationId(),
                                    "rev",
                                    operation.revision(),
                                    "pid",
                                    impl.projectId(),
                                    "branch",
                                    impl.branch(),
                                    "commit",
                                    impl.commitId(),
                                    "sig",
                                    impl.signature().replaceAll("\\s+", "")))
                    .consume();
        }
    }

    public void refreshImplementationLinks() {
        for (Operation operation : operations()) {
            try (var session = driver.session()) {
                session.executeWrite(
                        tx -> {
                            projectImplementations(tx, operation);
                            return null;
                        });
            }
        }
    }

    public List<FlowRevision> flows() {
        initialize();
        try (var session = driver.session()) {
            return session.run(
                            """
                            MATCH (f:BusinessFlow) WHERE f.revision>0
                            RETURN f.definition AS json,f.hash AS hash,f.status AS status,
                                   f.confirmedBy AS reviewer,f.confirmedAt AS at ORDER BY f.flowId,f.revision DESC
                            """)
                    .list(this::flowRecord);
        }
    }

    public FlowRevision flow(String id, int revision) {
        initialize();
        try (var session = driver.session()) {
            var rows =
                    session.run(
                                    """
                                    MATCH (f:BusinessFlow {flowId:$id,revision:$rev}) WHERE f.revision>0
                                    RETURN f.definition AS json,f.hash AS hash,f.status AS status,f.confirmedBy AS reviewer,f.confirmedAt AS at
                                    """,
                                    Map.of("id", id, "rev", revision))
                            .list();
            require(!rows.isEmpty(), "Unknown flow revision");
            return flowRecord(rows.getFirst());
        }
    }

    private FlowRevision flowRecord(org.neo4j.driver.Record r) {
        return new FlowRevision(
                GSON.fromJson(r.get("json").asString(), Flow.class),
                r.get("hash").asString(),
                r.get("status").asString(),
                r.get("reviewer").asString(null),
                r.get("at").asString(null));
    }

    /** Input revision is the expected current revision, not the revision to overwrite. */
    public FlowRevision saveDraft(Flow input) {
        initialize();
        Flow flow =
                new Flow(
                        input.flowId(),
                        input.revision() + 1,
                        input.name(),
                        input.initialContext(),
                        input.nodes(),
                        input.dependencies(),
                        input.stateModels(),
                        input.testPoints(), input.steps(), input.cleanup());
        String hash = hash(flow);
        try (var session = driver.session()) {
            session.executeWrite(
                    tx -> {
                        var params =
                                Map.<String, Object>of(
                                        "id",
                                        flow.flowId(),
                                        "expected",
                                        input.revision(),
                                        "rev",
                                        flow.revision(),
                                        "json",
                                        canonical(flow),
                                        "hash",
                                        hash);
                        var lock =
                                tx.run(
                                                """
                                                MERGE (head:BusinessFlow {flowId:$id,revision:0}) ON CREATE SET head.latestRevision=0
                                                SET head.writeLock=coalesce(head.writeLock,0)+1
                                                RETURN head.latestRevision AS latest
                                                """,
                                                params)
                                        .single()
                                        .get("latest")
                                        .asInt();
                        require(
                                lock == input.revision(),
                                "Flow changed concurrently; reload before saving");
                        tx.run(
                                        """
                                        CREATE (f:BusinessFlow {flowId:$id,revision:$rev,definition:$json,hash:$hash,status:'DRAFT'})
                                        WITH f MATCH (head:BusinessFlow {flowId:$id,revision:0}) SET head.latestRevision=$rev
                                        """,
                                        params)
                                .consume();
                        projectFlow(tx, flow);
                        return null;
                    });
        }
        return new FlowRevision(flow, hash, "DRAFT", null, null);
    }

    public FlowRevision decide(
            String id, int revision, String expectedHash, String reviewer, boolean accept) {
        require(
                reviewer != null && !reviewer.isBlank(),
                "Authenticated reviewer or BUSINESS_REVIEWER configuration required");
        initialize();
        try (var session = driver.session()) {
            session.executeWrite(
                    tx -> {
                        var rows =
                                tx.run(
                                                """
                                                MATCH (f:BusinessFlow {flowId:$id,revision:$rev})
                                                SET f.writeLock=coalesce(f.writeLock,0)+1
                                                WITH f WHERE f.hash=$hash AND f.status='DRAFT'
                                                SET f.status=$status,f.confirmedBy=$reviewer,f.confirmedAt=$at RETURN f.hash
                                                """,
                                                Map.of(
                                                        "id",
                                                        id,
                                                        "rev",
                                                        revision,
                                                        "hash",
                                                        expectedHash,
                                                        "status",
                                                        accept ? "CONFIRMED" : "REJECTED",
                                                        "reviewer",
                                                        reviewer,
                                                        "at",
                                                        Instant.now().toString()))
                                        .list();
                        require(rows.size() == 1, "Stale hash or revision already decided");
                        return null;
                    });
        }
        return flow(id, revision);
    }

    private static void projectFlow(TransactionContext tx, Flow flow) {
        Map<String, Object> scope = Map.of("id", flow.flowId(), "rev", flow.revision());
        for (var variable : flow.initialContext().entrySet())
            variable(tx, scope, variable.getKey(), variable.getValue());
        for (var node : flow.nodes()) {
            var p = new HashMap<>(scope);
            p.put("node", node.id());
            p.put("json", canonical(node));
            int at = node.operationRef().lastIndexOf('@');
            p.put("op", node.operationRef().substring(0, at));
            p.put("oprev", node.operationRef().substring(at + 1));
            tx.run(
                            """
                            MATCH (f:BusinessFlow {flowId:$id,revision:$rev})
                            MATCH (o:ApiOperation {operationId:$op,revision:$oprev})
                            CREATE (n:FlowNode {flowId:$id,flowRevision:$rev,nodeId:$node,definition:$json})
                            CREATE (f)-[:CONTAINS]->(n) CREATE (n)-[:USES_OPERATION]->(o)
                            """,
                            p)
                    .consume();
            for (var output : node.outputs()) {
                variable(tx, scope, output.key(), new Variable(output.type(), output.sensitive()));
                p.put("key", output.key());
                tx.run(
                                "MATCH (n:FlowNode"
                                    + " {flowId:$id,flowRevision:$rev,nodeId:$node}),(v:ContextVariable"
                                    + " {flowId:$id,flowRevision:$rev,key:$key}) MERGE"
                                    + " (n)-[:PRODUCES]->(v)",
                                p)
                        .consume();
            }
        }
        for (var node : flow.nodes()) {
            Set<String> required = new TreeSet<>();
            node.inputs().values().stream()
                    .filter(b -> "context".equals(b.source()))
                    .forEach(b -> required.add(String.valueOf(b.value())));
            node.states()
                    .forEach(
                            s -> {
                                required.add(s.entityContext());
                                required.add(s.observedContext());
                                required.add(s.observedEntityContext());
                            });
            for (String key : required) {
                var p = new HashMap<>(scope);
                p.put("node", node.id());
                p.put("key", key);
                tx.run(
                                "MATCH (n:FlowNode"
                                    + " {flowId:$id,flowRevision:$rev,nodeId:$node}),(v:ContextVariable"
                                    + " {flowId:$id,flowRevision:$rev,key:$key}) MERGE"
                                    + " (n)-[:REQUIRES]->(v)",
                                p)
                        .consume();
            }
        }
        for (var edge : flow.dependencies()) {
            var p = new HashMap<>(scope);
            p.put("from", edge.from());
            p.put("to", edge.to());
            p.put("kind", edge.kind());
            p.put("reason", edge.reason());
            tx.run(
                            "MATCH (a:FlowNode"
                                    + " {flowId:$id,flowRevision:$rev,nodeId:$from}),(b:FlowNode"
                                    + " {flowId:$id,flowRevision:$rev,nodeId:$to}) CREATE"
                                    + " (b)-[:DEPENDS_ON {kind:$kind,reason:$reason}]->(a)",
                            p)
                    .consume();
        }
        for (var model : flow.stateModels()) {
            var p = new HashMap<>(scope);
            p.put("model", model.modelId());
            p.put("modelrev", model.revision());
            p.put("json", canonical(model));
            var stored =
                    tx.run(
                                    "MERGE (s:StateModel {modelId:$model,revision:$modelrev}) ON"
                                        + " CREATE SET s.definition=$json RETURN s.definition AS"
                                        + " json",
                                    p)
                            .single()
                            .get("json")
                            .asString();
            require(stored.equals(canonical(model)), "State model revision is immutable");
            for (var node : flow.nodes())
                for (var constraint : node.states())
                    if (constraint.modelId().equals(model.modelId())) {
                        p.put("node", node.id());
                        p.put("rule", constraint.ruleId());
                        tx.run(
                                        "MATCH (n:FlowNode"
                                            + " {flowId:$id,flowRevision:$rev,nodeId:$node}),(s:StateModel"
                                            + " {modelId:$model,revision:$modelrev}) MERGE"
                                            + " (n)-[:USES_STATE_MODEL {ruleId:$rule}]->(s)",
                                        p)
                                .consume();
                    }
        }
        for (var point : flow.testPoints()) {
            var p = new HashMap<>(scope);
            p.put("point", point.id());
            p.put("json", canonical(point));
            tx.run(
                            "MATCH (f:BusinessFlow {flowId:$id,revision:$rev}) CREATE (p:TestPoint"
                                + " {flowId:$id,flowRevision:$rev,pointId:$point,definition:$json})"
                                + " CREATE (f)-[:HAS_TEST_POINT]->(p)",
                            p)
                    .consume();
            for (String covered : point.covers()) {
                p.put("node", covered);
                tx.run(
                                "MATCH (p:TestPoint"
                                    + " {flowId:$id,flowRevision:$rev,pointId:$point}),(n:FlowNode"
                                    + " {flowId:$id,flowRevision:$rev,nodeId:$node}) CREATE"
                                    + " (p)-[:COVERS]->(n)",
                                p)
                        .consume();
            }
        }
    }

    private static void variable(
            TransactionContext tx, Map<String, Object> scope, String key, Variable variable) {
        var p = new HashMap<>(scope);
        p.put("key", key);
        p.put("type", variable.type());
        p.put("sensitive", variable.sensitive());
        tx.run(
                        "MERGE (v:ContextVariable {flowId:$id,flowRevision:$rev,key:$key}) SET"
                                + " v.type=$type,v.sensitive=$sensitive",
                        p)
                .consume();
    }
}
